package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 施工段闭锁与可用区域服务：受控跑道登记、施工单登记/修改/生效/撤回、分段验收与可用区间计算。
 *
 * <p>并发与一致性要点：</p>
 * <ul>
 *   <li>施工生效、最后验收与整跑道恢复在同一跑道上先取 runway_lock 行锁串行化，按事务次序判定，
 *       不留带活动闭锁的新开放。</li>
 *   <li>施工单与受控跑道聚合带乐观锁 version（从1开始，成功变更加1），写操作携带 expectedVersion，不符返回409。</li>
 *   <li>生效在同一事务内冻结区间、闭锁跑道并原子中止/取消该跑道例行计划（保留证据），不波及其他跑道。</li>
 *   <li>可用区间按所有活动施工区间并集的补集计算，重叠不重复计长度。</li>
 * </ul>
 */
@Service
public class ConstructionService {

    static final String SCOPE_RUNWAY_REGISTER = "runway-register";
    static final String SCOPE_RUNWAY_RESIZE = "runway-resize";
    static final String SCOPE_ORDER_CREATE = "construction-create";
    static final String SCOPE_ORDER_MODIFY = "construction-modify";
    static final String SCOPE_ORDER_ACTIVATE = "construction-activate";
    static final String SCOPE_ORDER_WITHDRAW = "construction-withdraw";
    static final String SCOPE_ACCEPTANCE = "segment-acceptance";

    private final ControlledRunwayRepository runwayRepository;
    private final ConstructionOrderRepository orderRepository;
    private final ConstructionSegmentRepository segmentRepository;
    private final AcceptanceCredentialRepository credentialRepository;
    private final RoutinePlanRepository routinePlanRepository;
    private final RunwayLockRepository runwayLockRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final Clock clock;

    public ConstructionService(ControlledRunwayRepository runwayRepository,
                               ConstructionOrderRepository orderRepository,
                               ConstructionSegmentRepository segmentRepository,
                               AcceptanceCredentialRepository credentialRepository,
                               RoutinePlanRepository routinePlanRepository,
                               RunwayLockRepository runwayLockRepository,
                               IdempotencyExecutor idempotencyExecutor,
                               Clock clock) {
        this.runwayRepository = runwayRepository;
        this.orderRepository = orderRepository;
        this.segmentRepository = segmentRepository;
        this.credentialRepository = credentialRepository;
        this.routinePlanRepository = routinePlanRepository;
        this.runwayLockRepository = runwayLockRepository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.clock = clock;
    }

    // ============================= 受控跑道登记与长度变更 =============================

    /**
     * 登记受控跑道：正整数米长度，初始版本1；重复登记（异requestId）返回409。requestId 幂等。
     */
    public ControlledRunwayResponse registerRunway(RunwayRegisterRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_RUNWAY_REGISTER,
                request.requestId(), request.runwayId(), String.valueOf(request.lengthMeters()));
        try {
            return idempotencyExecutor.execute(SCOPE_RUNWAY_REGISTER, request.requestId(), fingerprint,
                    () -> doRegisterRunway(request), ControlledRunwayResponse.class);
        } catch (DuplicateKeyException e) {
            ControlledRunwayResponse replayed = idempotencyExecutor.tryReplay(SCOPE_RUNWAY_REGISTER,
                    request.requestId(), fingerprint, ControlledRunwayResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw ApiException.conflict("RUNWAY_ALREADY_REGISTERED", "跑道已登记为受控跑道: " + request.runwayId());
        }
    }

    private ControlledRunwayResponse doRegisterRunway(RunwayRegisterRequest request) {
        if (runwayRepository.findById(request.runwayId()).isPresent()) {
            throw ApiException.conflict("RUNWAY_ALREADY_REGISTERED", "跑道已登记为受控跑道: " + request.runwayId());
        }
        Instant now = clock.instant();
        ControlledRunway saved = runwayRepository.insert(new ControlledRunway(request.runwayId(),
                request.lengthMeters(), 1, request.requestId(), now, now));
        return toResponse(saved);
    }

    /**
     * 变更跑道长度：expectedVersion 乐观锁；存在活动施工后不许缩改（缩短拒绝，扩建允许）。requestId 幂等。
     */
    public ControlledRunwayResponse resizeRunway(String runwayId, RunwayResizeRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_RUNWAY_RESIZE,
                request.requestId(), runwayId, String.valueOf(request.expectedVersion()),
                String.valueOf(request.lengthMeters()));
        return idempotencyExecutor.execute(SCOPE_RUNWAY_RESIZE, request.requestId(), fingerprint,
                () -> doResizeRunway(runwayId, request), ControlledRunwayResponse.class);
    }

    private ControlledRunwayResponse doResizeRunway(String runwayId, RunwayResizeRequest request) {
        ControlledRunway runway = runwayRepository.findByIdForUpdate(runwayId)
                .orElseThrow(() -> ApiException.notFound("CONTROLLED_RUNWAY_NOT_FOUND",
                        "受控跑道不存在: " + runwayId));
        if (runway.version() != request.expectedVersion()) {
            throw ApiException.conflict("RUNWAY_VERSION_CONFLICT",
                    "跑道版本已变更，当前=" + runway.version() + "，expectedVersion=" + request.expectedVersion());
        }
        if (request.lengthMeters() < runway.lengthMeters()
                && orderRepository.existsActiveByRunway(runwayId)) {
            throw ApiException.unprocessable("RUNWAY_SHRINK_BLOCKED",
                    "存在活动施工，不许缩改跑道长度");
        }
        runwayRepository.updateLength(runwayId, request.lengthMeters(), clock.instant());
        return toResponse(runwayRepository.findById(runwayId).orElseThrow());
    }

    /**
     * 受控跑道详情。
     */
    @Transactional(readOnly = true)
    public ControlledRunwayResponse getRunway(String runwayId) {
        return toResponse(runwayRepository.findById(runwayId)
                .orElseThrow(() -> ApiException.notFound("CONTROLLED_RUNWAY_NOT_FOUND",
                        "受控跑道不存在: " + runwayId)));
    }

    // ============================= 施工单登记与修改 =============================

    /**
     * 登记施工单：引用受控跑道及1至10个不同段号；同单内部重叠拒绝，端点相接允许。
     * 初始版本1，状态 PENDING（未生效）。requestId 幂等。
     */
    public ConstructionOrderResponse createOrder(ConstructionOrderCreateRequest request) {
        List<List<String>> segmentFields = request.segments().stream()
                .map(s -> List.of(String.valueOf(s.segmentNo()), String.valueOf(s.startMeters()),
                        String.valueOf(s.endMeters())))
                .toList();
        String fingerprint = StructuredFingerprint.sha256WithCollection(SCOPE_ORDER_CREATE,
                List.of(request.requestId(), request.runwayId()), segmentFields);
        return idempotencyExecutor.execute(SCOPE_ORDER_CREATE, request.requestId(), fingerprint,
                () -> doCreateOrder(request), ConstructionOrderResponse.class);
    }

    private ConstructionOrderResponse doCreateOrder(ConstructionOrderCreateRequest request) {
        ControlledRunway runway = runwayRepository.findByIdForUpdate(request.runwayId())
                .orElseThrow(() -> ApiException.unprocessable("RUNWAY_NOT_CONTROLLED",
                        "跑道未登记为受控跑道，不受理施工单: " + request.runwayId()));
        validateSegments(request.segments(), runway.lengthMeters());
        Instant now = clock.instant();
        ConstructionOrder order = orderRepository.insert(new ConstructionOrder(0L, request.runwayId(),
                ConstructionOrderStatus.PENDING, 1, null, null, null, request.requestId(), now, now));
        insertSegments(order.id(), request.segments(), now);
        return toResponse(orderRepository.findById(order.id()).orElseThrow());
    }

    /**
     * 修改施工单：仅未生效（PENDING）单据可整体替换施工段；已生效不得缩段或删除。
     * expectedVersion 乐观锁，成功版本加1。requestId 幂等。
     */
    public ConstructionOrderResponse modifyOrder(long orderId, ConstructionOrderModifyRequest request) {
        List<List<String>> segmentFields = request.segments().stream()
                .map(s -> List.of(String.valueOf(s.segmentNo()), String.valueOf(s.startMeters()),
                        String.valueOf(s.endMeters())))
                .toList();
        String fingerprint = StructuredFingerprint.sha256WithCollection(SCOPE_ORDER_MODIFY,
                List.of(request.requestId(), String.valueOf(orderId), String.valueOf(request.expectedVersion())),
                segmentFields);
        return idempotencyExecutor.execute(SCOPE_ORDER_MODIFY, request.requestId(), fingerprint,
                () -> doModifyOrder(orderId, request), ConstructionOrderResponse.class);
    }

    private ConstructionOrderResponse doModifyOrder(long orderId, ConstructionOrderModifyRequest request) {
        ConstructionOrder order = mustFindOrderForUpdate(orderId);
        requireVersion(order, request.expectedVersion());
        if (order.status() != ConstructionOrderStatus.PENDING) {
            throw ApiException.conflict("CONSTRUCTION_ORDER_NOT_PENDING",
                    "已生效施工单不得修改或缩段，当前状态=" + order.status());
        }
        ControlledRunway runway = runwayRepository.findByIdForUpdate(order.runwayId()).orElseThrow();
        validateSegments(request.segments(), runway.lengthMeters());
        Instant now = clock.instant();
        segmentRepository.deleteByOrderId(orderId);
        insertSegments(orderId, request.segments(), now);
        orderRepository.update(new ConstructionOrder(order.id(), order.runwayId(), order.status(),
                order.version(), order.activatedAt(), order.completedAt(), order.withdrawnAt(),
                order.requestId(), order.createdAt(), now));
        return toResponse(orderRepository.findById(orderId).orElseThrow());
    }

    // ============================= 施工单生效与撤回 =============================

    /**
     * 施工单生效：冻结区间并阻断整跑道新使用；同一事务内原子中止该跑道已开始例行计划、
     * 取消未开始项并保留证据，不波及其他跑道。与整跑道恢复按跑道行锁串行化。requestId 幂等。
     */
    public ConstructionOrderResponse activateOrder(long orderId, ConstructionActivateRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_ORDER_ACTIVATE,
                request.requestId(), String.valueOf(orderId), String.valueOf(request.expectedVersion()),
                request.activatedAt().toString());
        return idempotencyExecutor.execute(SCOPE_ORDER_ACTIVATE, request.requestId(), fingerprint,
                () -> doActivateOrder(orderId, request), ConstructionOrderResponse.class);
    }

    private ConstructionOrderResponse doActivateOrder(long orderId, ConstructionActivateRequest request) {
        ConstructionOrder order = mustFindOrderForUpdate(orderId);
        requireVersion(order, request.expectedVersion());
        if (order.status() != ConstructionOrderStatus.PENDING) {
            throw ApiException.conflict("CONSTRUCTION_ORDER_NOT_PENDING",
                    "仅未生效施工单可生效，当前状态=" + order.status());
        }
        // 与整跑道恢复/例行开始在同一跑道上串行化；并锁定受控跑道行重读长度，防止生效与缩改竞争。
        runwayLockRepository.lockRunway(order.runwayId());
        ControlledRunway runway = runwayRepository.findByIdForUpdate(order.runwayId()).orElseThrow();
        List<ConstructionSegment> segments = segmentRepository.findByOrderId(orderId);
        for (ConstructionSegment segment : segments) {
            if (segment.endMeters() > runway.lengthMeters()) {
                throw ApiException.unprocessable("CONSTRUCTION_SEGMENT_RANGE_INVALID",
                        "施工段终点超出跑道当前长度：段" + segment.segmentNo() + " 终点 "
                                + segment.endMeters() + " > 长度 " + runway.lengthMeters());
            }
        }
        Instant now = clock.instant();
        orderRepository.update(new ConstructionOrder(order.id(), order.runwayId(),
                ConstructionOrderStatus.ACTIVE, order.version(), request.activatedAt(), null, null,
                order.requestId(), order.createdAt(), now));
        preemptRoutinePlans(order.runwayId(), request.activatedAt(), now);
        return toResponse(orderRepository.findById(orderId).orElseThrow());
    }

    /**
     * 撤回施工单：仅未生效（PENDING）单据可撤回；已生效不得直接删除或缩段。requestId 幂等。
     */
    public ConstructionOrderResponse withdrawOrder(long orderId, ConstructionWithdrawRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_ORDER_WITHDRAW,
                request.requestId(), String.valueOf(orderId), String.valueOf(request.expectedVersion()));
        return idempotencyExecutor.execute(SCOPE_ORDER_WITHDRAW, request.requestId(), fingerprint,
                () -> doWithdrawOrder(orderId, request), ConstructionOrderResponse.class);
    }

    private ConstructionOrderResponse doWithdrawOrder(long orderId, ConstructionWithdrawRequest request) {
        ConstructionOrder order = mustFindOrderForUpdate(orderId);
        requireVersion(order, request.expectedVersion());
        if (order.status() != ConstructionOrderStatus.PENDING) {
            throw ApiException.conflict("CONSTRUCTION_ORDER_NOT_PENDING",
                    "已生效施工单不得撤回或删除，当前状态=" + order.status());
        }
        Instant now = clock.instant();
        orderRepository.update(new ConstructionOrder(order.id(), order.runwayId(),
                ConstructionOrderStatus.WITHDRAWN, order.version(), null, null, now,
                order.requestId(), order.createdAt(), now));
        return toResponse(orderRepository.findById(orderId).orElseThrow());
    }

    // ============================= 分段验收 =============================

    /**
     * 提交验收清场凭据（合成）：每段由两个不同验收人各提交一条后完成，仅消除本单该段闭锁；
     * 重叠区域仍被其他闭锁段占用时不可用。已结束施工的迟到凭据只读留痕，不复活已中止巡检。
     * 与整跑道恢复按跑道行锁串行化。requestId 幂等。
     */
    public AcceptanceResponse submitAcceptance(long orderId, int segmentNo, AcceptanceRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_ACCEPTANCE,
                request.requestId(), String.valueOf(orderId), String.valueOf(segmentNo),
                request.inspectorId(), request.credential(), request.submittedAt().toString());
        return idempotencyExecutor.execute(SCOPE_ACCEPTANCE, request.requestId(), fingerprint,
                () -> doSubmitAcceptance(orderId, segmentNo, request), AcceptanceResponse.class);
    }

    private AcceptanceResponse doSubmitAcceptance(long orderId, int segmentNo, AcceptanceRequest request) {
        ConstructionOrder order = mustFindOrderForUpdate(orderId);
        // 最后验收与整跑道恢复/新施工生效在同一跑道上串行化，按事务次序判定。
        runwayLockRepository.lockRunway(order.runwayId());
        ConstructionSegment segment = segmentRepository
                .findByOrderIdAndSegmentNoForUpdate(orderId, segmentNo)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_SEGMENT_NOT_FOUND",
                        "施工段不存在: 单" + orderId + " 段" + segmentNo));
        Instant now = clock.instant();
        if (order.status() == ConstructionOrderStatus.WITHDRAWN) {
            throw ApiException.conflict("CONSTRUCTION_ORDER_WITHDRAWN", "施工单已撤回，不受理验收凭据");
        }
        if (order.status() == ConstructionOrderStatus.PENDING) {
            throw ApiException.unprocessable("CONSTRUCTION_ORDER_NOT_ACTIVE",
                    "施工单尚未生效，不受理验收凭据");
        }
        if (order.status() == ConstructionOrderStatus.COMPLETED
                || segment.status() == ConstructionSegmentStatus.COMPLETED) {
            // 已结束施工/已完成段的迟到凭据：只读留痕，不改变状态、不复活已中止巡检。
            AcceptanceCredential late = credentialRepository.insert(new AcceptanceCredential(0L,
                    segment.id(), orderId, request.inspectorId(), request.credential(),
                    request.submittedAt(), true, request.requestId(), now));
            return new AcceptanceResponse(late.id(), orderId, segmentNo,
                    ConstructionSegmentStatus.COMPLETED.name(), order.status().name(), true, order.version());
        }
        boolean inspectorAlreadySubmitted = credentialRepository.findBySegmentId(segment.id()).stream()
                .filter(c -> !c.late())
                .anyMatch(c -> c.inspectorId().equals(request.inspectorId()));
        if (inspectorAlreadySubmitted) {
            throw ApiException.conflict("ACCEPTANCE_INSPECTOR_DUPLICATE",
                    "同一验收人不得重复提交同段凭据: " + request.inspectorId());
        }
        AcceptanceCredential saved = credentialRepository.insert(new AcceptanceCredential(0L,
                segment.id(), orderId, request.inspectorId(), request.credential(),
                request.submittedAt(), false, request.requestId(), now));
        long distinctInspectors = credentialRepository.findBySegmentId(segment.id()).stream()
                .filter(c -> !c.late())
                .map(AcceptanceCredential::inspectorId)
                .distinct()
                .count();
        ConstructionOrder current = order;
        ConstructionSegmentStatus segmentStatus = segment.status();
        if (distinctInspectors >= 2) {
            // 两个不同验收人凭据齐全：本段完成，仅消除本单该段闭锁。
            segmentRepository.markCompleted(segment.id(), request.submittedAt(), now);
            segmentStatus = ConstructionSegmentStatus.COMPLETED;
            boolean allCompleted = segmentRepository.findByOrderId(orderId).stream()
                    .allMatch(s -> s.status() == ConstructionSegmentStatus.COMPLETED);
            if (allCompleted) {
                orderRepository.update(new ConstructionOrder(order.id(), order.runwayId(),
                        ConstructionOrderStatus.COMPLETED, order.version(), order.activatedAt(),
                        request.submittedAt(), null, order.requestId(), order.createdAt(), now));
                current = orderRepository.findById(orderId).orElseThrow();
            }
        }
        return new AcceptanceResponse(saved.id(), orderId, segmentNo, segmentStatus.name(),
                current.status().name(), false, current.version());
    }

    // ============================= 查询 =============================

    /**
     * 施工单详情（含施工段与已登记凭据数）。
     */
    @Transactional(readOnly = true)
    public ConstructionOrderResponse getOrder(long orderId) {
        return toResponse(orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_ORDER_NOT_FOUND",
                        "施工单不存在: " + orderId)));
    }

    /**
     * 按跑道列出施工单。
     */
    @Transactional(readOnly = true)
    public List<ConstructionOrderResponse> listOrders(String runwayId) {
        return orderRepository.findByRunway(runwayId).stream().map(this::toResponse).toList();
    }

    /**
     * 可用区域查询：可用区间为所有活动施工区间并集的补集；重叠不重复计长度；空可用返回空列表。
     */
    @Transactional(readOnly = true)
    public AvailableAreasResponse getAvailableAreas(String runwayId) {
        ControlledRunway runway = runwayRepository.findById(runwayId)
                .orElseThrow(() -> ApiException.notFound("CONTROLLED_RUNWAY_NOT_FOUND",
                        "受控跑道不存在: " + runwayId));
        List<IntervalView> union = mergeIntervals(segmentRepository.findLockedByRunway(runwayId).stream()
                .map(s -> new IntervalView(s.startMeters(), s.endMeters(),
                        s.endMeters() - s.startMeters()))
                .toList());
        List<IntervalView> available = new ArrayList<>();
        int cursor = 0;
        for (IntervalView locked : union) {
            if (locked.startMeters() > cursor) {
                available.add(new IntervalView(cursor, locked.startMeters(),
                        locked.startMeters() - cursor));
            }
            cursor = Math.max(cursor, locked.endMeters());
        }
        if (cursor < runway.lengthMeters()) {
            available.add(new IntervalView(cursor, runway.lengthMeters(),
                    runway.lengthMeters() - cursor));
        }
        int unavailableTotal = union.stream().mapToInt(IntervalView::lengthMeters).sum();
        int availableTotal = available.stream().mapToInt(IntervalView::lengthMeters).sum();
        return new AvailableAreasResponse(runwayId, runway.lengthMeters(), union, available,
                unavailableTotal, availableTotal);
    }

    // ============================= 内部工具 =============================

    /**
     * 施工生效抢占：同一事务内取消该跑道“尚未开始”例行计划，已开始计划转中止并保留已完成检查项与证据。
     * 被抢占计划置终态并释放获准位（不复活被抢占项）。
     */
    private void preemptRoutinePlans(String runwayId, Instant preemptedAt, Instant now) {
        List<RoutinePlan> activePlans = routinePlanRepository.findActiveByRunway(runwayId);
        for (RoutinePlan plan : activePlans) {
            RoutinePlanStatus target = switch (plan.status()) {
                case STARTED -> RoutinePlanStatus.ABORTED;
                case APPROVED, NEEDS_REVIEW -> RoutinePlanStatus.CANCELLED;
                default -> null;
            };
            if (target == null) {
                continue;
            }
            RoutinePlan preempted = new RoutinePlan(plan.id(), plan.runwayId(), plan.windowStart(),
                    plan.windowEnd(), plan.durationMinutes(), plan.calendarVersion(), plan.version() + 1,
                    target, plan.evidence(), plan.slotFlag(), plan.emergencyEventId(), plan.startedAt(),
                    plan.completedAt(), preemptedAt, plan.requestId(), plan.createdAt(), now);
            routinePlanRepository.update(preempted);
        }
    }

    /**
     * 校验施工段集合：1至10个不同段号；0<=start<end<=跑道长度；同单内部重叠拒绝，端点相接允许。
     */
    private void validateSegments(List<ConstructionSegmentInput> segments, int lengthMeters) {
        if (segments.isEmpty() || segments.size() > 10) {
            throw ApiException.unprocessable("CONSTRUCTION_SEGMENT_COUNT_INVALID",
                    "施工单须包含1至10个施工段，当前=" + segments.size());
        }
        Set<Integer> seenNos = new HashSet<>();
        for (ConstructionSegmentInput segment : segments) {
            if (!seenNos.add(segment.segmentNo())) {
                throw ApiException.unprocessable("CONSTRUCTION_SEGMENT_NO_DUPLICATE",
                        "同一施工单内段号不得重复: " + segment.segmentNo());
            }
            if (segment.startMeters() < 0 || segment.startMeters() >= segment.endMeters()
                    || segment.endMeters() > lengthMeters) {
                throw ApiException.unprocessable("CONSTRUCTION_SEGMENT_RANGE_INVALID",
                        "施工段区间须满足 0<=start<end<=跑道长度(" + lengthMeters + ")：段"
                                + segment.segmentNo() + " [" + segment.startMeters() + ","
                                + segment.endMeters() + ")");
            }
        }
        List<ConstructionSegmentInput> sorted = segments.stream()
                .sorted(Comparator.comparingInt(ConstructionSegmentInput::startMeters))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startMeters() < sorted.get(i - 1).endMeters()) {
                throw ApiException.unprocessable("CONSTRUCTION_SEGMENT_OVERLAP",
                        "同一施工单内部区间重叠：段" + sorted.get(i - 1).segmentNo() + " 与段"
                                + sorted.get(i).segmentNo());
            }
        }
    }

    private void insertSegments(long orderId, List<ConstructionSegmentInput> segments, Instant now) {
        for (ConstructionSegmentInput input : segments) {
            segmentRepository.insert(new ConstructionSegment(0L, orderId, input.segmentNo(),
                    input.startMeters(), input.endMeters(), ConstructionSegmentStatus.LOCKED,
                    null, now, now));
        }
    }

    /** 合并重叠/相接区间（并集口径，重叠不重复计长度），按起点排序返回。 */
    private List<IntervalView> mergeIntervals(List<IntervalView> intervals) {
        List<IntervalView> sorted = intervals.stream()
                .sorted(Comparator.comparingInt(IntervalView::startMeters))
                .toList();
        List<IntervalView> merged = new ArrayList<>();
        for (IntervalView interval : sorted) {
            if (!merged.isEmpty() && interval.startMeters() <= merged.getLast().endMeters()) {
                IntervalView last = merged.removeLast();
                int end = Math.max(last.endMeters(), interval.endMeters());
                merged.add(new IntervalView(last.startMeters(), end, end - last.startMeters()));
            } else {
                merged.add(interval);
            }
        }
        return merged;
    }

    private ConstructionOrder mustFindOrderForUpdate(long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_ORDER_NOT_FOUND",
                        "施工单不存在: " + orderId));
    }

    private void requireVersion(ConstructionOrder order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("CONSTRUCTION_VERSION_CONFLICT",
                    "施工单版本已变更，当前=" + order.version() + "，expectedVersion=" + expectedVersion);
        }
    }

    private ControlledRunwayResponse toResponse(ControlledRunway runway) {
        return new ControlledRunwayResponse(runway.runwayId(), runway.lengthMeters(), runway.version(),
                runway.createdAt(), runway.updatedAt());
    }

    private ConstructionOrderResponse toResponse(ConstructionOrder order) {
        List<ConstructionSegment> segments = segmentRepository.findByOrderId(order.id());
        Map<Long, Long> acceptanceCounts = credentialRepository.findByOrderId(order.id()).stream()
                .filter(c -> !c.late())
                .collect(Collectors.groupingBy(AcceptanceCredential::segmentId, Collectors.counting()));
        List<SegmentView> views = segments.stream()
                .map(s -> new SegmentView(s.segmentNo(), s.startMeters(), s.endMeters(), s.status().name(),
                        s.completedAt(), acceptanceCounts.getOrDefault(s.id(), 0L).intValue()))
                .toList();
        return new ConstructionOrderResponse(order.id(), order.runwayId(), order.status().name(),
                order.version(), views, order.activatedAt(), order.completedAt(), order.withdrawnAt(),
                order.createdAt(), order.updatedAt());
    }
}
