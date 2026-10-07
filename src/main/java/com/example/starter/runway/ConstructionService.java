package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 跑道施工段闭锁与可用区域查询服务：受控跑道登记、施工单生命周期、段验收、可用区间并集补集。
 *
 * <p>并发与一致性要点：</p>
 * <ul>
 *   <li>施工生效、长度缩改、恢复开放、最后验收在同一跑道上先取 runway_lock 行锁串行化，按事务次序判定，
 *       不留带活动闭锁的新开放。</li>
 *   <li>施工单/受控跑道聚合带乐观锁 version（从1开始），写操作携带 expectedVersion，不符返回409。</li>
 *   <li>生效在同一事务内冻结区间、原子中止/取消本跑道例行计划并保留证据，不波及其他跑道。</li>
 *   <li>全部写操作 requestId 幂等：指纹覆盖全部业务字段，同键同参重放，异参409，失败不占键。</li>
 * </ul>
 */
@Service
public class ConstructionService {

    static final String SCOPE_RUNWAY_REGISTER = "runway-register";
    static final String SCOPE_RUNWAY_UPDATE = "runway-update";
    static final String SCOPE_ORDER_CREATE = "construction-create";
    static final String SCOPE_ORDER_UPDATE = "construction-update";
    static final String SCOPE_ORDER_ACTIVATE = "construction-activate";
    static final String SCOPE_ORDER_WITHDRAW = "construction-withdraw";
    static final String SCOPE_CLEARANCE = "clearance-submit";

    /** 同一施工段完成所需的不同验收人数量。 */
    static final int REQUIRED_ACCEPTORS = 2;

    private final ControlledRunwayRepository runwayRepository;
    private final ConstructionOrderRepository orderRepository;
    private final ConstructionSegmentRepository segmentRepository;
    private final ClearanceCredentialRepository credentialRepository;
    private final RoutinePlanRepository routinePlanRepository;
    private final RunwayLockRepository runwayLockRepository;
    private final IdempotencyExecutor idempotencyExecutor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ConstructionService(ControlledRunwayRepository runwayRepository,
                               ConstructionOrderRepository orderRepository,
                               ConstructionSegmentRepository segmentRepository,
                               ClearanceCredentialRepository credentialRepository,
                               RoutinePlanRepository routinePlanRepository,
                               RunwayLockRepository runwayLockRepository,
                               IdempotencyExecutor idempotencyExecutor,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.runwayRepository = runwayRepository;
        this.orderRepository = orderRepository;
        this.segmentRepository = segmentRepository;
        this.credentialRepository = credentialRepository;
        this.routinePlanRepository = routinePlanRepository;
        this.runwayLockRepository = runwayLockRepository;
        this.idempotencyExecutor = idempotencyExecutor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ============================= 受控跑道登记 =============================

    /**
     * 登记受控跑道：正整数米长度，初始版本1；requestId 幂等；重复登记异参409。
     */
    public RunwayResponse registerRunway(RegisterRunwayRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_RUNWAY_REGISTER,
                request.requestId(), request.runwayId(), String.valueOf(request.lengthMeters()));
        try {
            return idempotencyExecutor.execute(SCOPE_RUNWAY_REGISTER, request.requestId(), fingerprint,
                    () -> doRegisterRunway(request), RunwayResponse.class);
        } catch (DuplicateKeyException e) {
            RunwayResponse replayed = idempotencyExecutor.tryReplay(SCOPE_RUNWAY_REGISTER,
                    request.requestId(), fingerprint, RunwayResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isControlledRunwayConflict(e)) {
                throw ApiException.conflict("RUNWAY_ALREADY_REGISTERED",
                        "跑道已登记为受控跑道: " + request.runwayId());
            }
            throw e;
        }
    }

    private RunwayResponse doRegisterRunway(RegisterRunwayRequest request) {
        Instant now = clock.instant();
        ControlledRunway runway = new ControlledRunway(request.runwayId(), request.lengthMeters(), 1,
                request.requestId(), now, now);
        runwayRepository.insert(runway);
        return toResponse(runwayRepository.findById(request.runwayId()).orElseThrow());
    }

    /**
     * 修改跑道长度：expectedVersion 乐观锁；存在活动施工后不许缩改（缩短拒绝，加长允许）。
     * 与施工生效在同一跑道行锁上串行化：缩短先生效则生效单按新长度重新校验，生效先提交则缩短被拒绝。
     */
    public RunwayResponse updateRunwayLength(String runwayId, UpdateRunwayLengthRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_RUNWAY_UPDATE,
                request.requestId(), runwayId, String.valueOf(request.expectedVersion()),
                String.valueOf(request.lengthMeters()));
        return executeIdempotent(SCOPE_RUNWAY_UPDATE, request.requestId(), fingerprint,
                () -> doUpdateRunwayLength(runwayId, request), RunwayResponse.class);
    }

    private RunwayResponse doUpdateRunwayLength(String runwayId, UpdateRunwayLengthRequest request) {
        runwayLockRepository.lockRunway(runwayId);
        ControlledRunway runway = runwayRepository.findByIdForUpdate(runwayId)
                .orElseThrow(() -> ApiException.notFound("RUNWAY_NOT_CONTROLLED",
                        "跑道未登记为受控跑道: " + runwayId));
        if (runway.version() != request.expectedVersion()) {
            throw ApiException.conflict("RUNWAY_VERSION_CONFLICT",
                    "跑道版本已变更，当前=" + runway.version() + "，expectedVersion=" + request.expectedVersion());
        }
        if (request.lengthMeters() < runway.lengthMeters()
                && orderRepository.existsEffectiveByRunway(runwayId)) {
            throw ApiException.unprocessable("RUNWAY_SHRINK_BLOCKED",
                    "存在活动施工，不许缩改跑道长度");
        }
        runwayRepository.updateLength(runwayId, request.lengthMeters(), clock.instant());
        return toResponse(runwayRepository.findById(runwayId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public RunwayResponse getRunway(String runwayId) {
        return toResponse(runwayRepository.findById(runwayId)
                .orElseThrow(() -> ApiException.notFound("RUNWAY_NOT_CONTROLLED",
                        "跑道未登记为受控跑道: " + runwayId)));
    }

    // ============================= 施工单登记与修改 =============================

    /**
     * 登记施工单：引用受控跑道及1至10个不同段号；同单内部重叠拒绝，端点相接允许。requestId 幂等。
     */
    public ConstructionOrderResponse createOrder(ConstructionOrderCreateRequest request) {
        List<List<String>> segmentFields = segmentFields(request.segments());
        String fingerprint = StructuredFingerprint.sha256WithCollection(SCOPE_ORDER_CREATE,
                List.of(request.requestId(), request.runwayId()), segmentFields);
        return executeIdempotent(SCOPE_ORDER_CREATE, request.requestId(), fingerprint,
                () -> doCreateOrder(request), ConstructionOrderResponse.class);
    }

    private ConstructionOrderResponse doCreateOrder(ConstructionOrderCreateRequest request) {
        ControlledRunway runway = runwayRepository.findById(request.runwayId())
                .orElseThrow(() -> ApiException.notFound("RUNWAY_NOT_CONTROLLED",
                        "跑道未登记为受控跑道: " + request.runwayId()));
        validateSegments(request.segments(), runway.lengthMeters());
        Instant now = clock.instant();
        ConstructionOrder order = orderRepository.insert(new ConstructionOrder(0L, request.runwayId(),
                ConstructionOrderStatus.DRAFT, 1, null, null, null, null, request.requestId(), now, now));
        insertSegments(order.id(), request.segments(), now);
        return toResponse(orderRepository.findById(order.id()).orElseThrow());
    }

    /**
     * 修改施工单（整体替换段集合）：仅未生效单可修改，已生效不得缩段；expectedVersion 乐观锁。requestId 幂等。
     */
    public ConstructionOrderResponse updateOrder(long orderId, ConstructionOrderUpdateRequest request) {
        List<List<String>> segmentFields = segmentFields(request.segments());
        String fingerprint = StructuredFingerprint.sha256WithCollection(SCOPE_ORDER_UPDATE,
                List.of(request.requestId(), String.valueOf(orderId), String.valueOf(request.expectedVersion())),
                segmentFields);
        return executeIdempotent(SCOPE_ORDER_UPDATE, request.requestId(), fingerprint,
                () -> doUpdateOrder(orderId, request), ConstructionOrderResponse.class);
    }

    private ConstructionOrderResponse doUpdateOrder(long orderId, ConstructionOrderUpdateRequest request) {
        ConstructionOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_ORDER_NOT_FOUND",
                        "施工单不存在: " + orderId));
        requireVersion(order, request.expectedVersion());
        if (order.status() != ConstructionOrderStatus.DRAFT) {
            throw ApiException.unprocessable("ORDER_NOT_MODIFIABLE",
                    "已生效施工单不得修改或缩段，当前状态=" + order.status());
        }
        ControlledRunway runway = runwayRepository.findById(order.runwayId()).orElseThrow();
        validateSegments(request.segments(), runway.lengthMeters());
        Instant now = clock.instant();
        segmentRepository.deleteByOrderId(orderId);
        insertSegments(orderId, request.segments(), now);
        orderRepository.update(new ConstructionOrder(order.id(), order.runwayId(), order.status(),
                order.version(), order.evidence(), order.activatedAt(), order.completedAt(),
                order.withdrawnAt(), order.requestId(), order.createdAt(), now));
        return toResponse(orderRepository.findById(orderId).orElseThrow());
    }

    // ============================= 施工单生效 / 撤回 =============================

    /**
     * 施工生效：冻结区间并阻断整跑道新使用；同一事务内原子中止已开始例行计划、取消未开始项并保留证据，
     * 不波及其他跑道。与恢复开放/最后验收按跑道行锁事务次序判定。requestId 幂等。
     */
    public ConstructionOrderResponse activateOrder(long orderId, OrderActionRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_ORDER_ACTIVATE,
                request.requestId(), String.valueOf(orderId), String.valueOf(request.expectedVersion()));
        return executeIdempotent(SCOPE_ORDER_ACTIVATE, request.requestId(), fingerprint,
                () -> doActivateOrder(orderId, request), ConstructionOrderResponse.class);
    }

    private ConstructionOrderResponse doActivateOrder(long orderId, OrderActionRequest request) {
        String runwayId = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_ORDER_NOT_FOUND",
                        "施工单不存在: " + orderId))
                .runwayId();
        // 与恢复开放、最后验收、长度缩改在同一跑道行锁上串行化，按事务次序判定。
        runwayLockRepository.lockRunway(runwayId);
        ConstructionOrder order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        requireVersion(order, request.expectedVersion());
        if (order.status() != ConstructionOrderStatus.DRAFT) {
            throw ApiException.unprocessable("ORDER_NOT_ACTIVATABLE",
                    "仅未生效施工单可生效，当前状态=" + order.status());
        }
        ControlledRunway runway = runwayRepository.findById(runwayId).orElseThrow();
        List<ConstructionSegment> segments = segmentRepository.findByOrderId(orderId);
        for (ConstructionSegment segment : segments) {
            if (segment.endMeter() > runway.lengthMeters()) {
                throw ApiException.unprocessable("SEGMENT_EXCEEDS_RUNWAY_LENGTH",
                        "施工段终点 " + segment.endMeter() + " 超出跑道长度 " + runway.lengthMeters());
            }
        }
        Instant now = clock.instant();
        PreemptionEvidence evidence = preemptRoutinePlans(order, now);
        segmentRepository.lockByOrderId(orderId, now);
        orderRepository.update(new ConstructionOrder(order.id(), order.runwayId(),
                ConstructionOrderStatus.EFFECTIVE, order.version(), writeEvidence(evidence), now, null, null,
                order.requestId(), order.createdAt(), now));
        return toResponse(orderRepository.findById(orderId).orElseThrow());
    }

    /**
     * 撤回施工单：仅未生效单可撤回；已生效不得直接删除。requestId 幂等。
     */
    public ConstructionOrderResponse withdrawOrder(long orderId, OrderActionRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_ORDER_WITHDRAW,
                request.requestId(), String.valueOf(orderId), String.valueOf(request.expectedVersion()));
        return executeIdempotent(SCOPE_ORDER_WITHDRAW, request.requestId(), fingerprint,
                () -> doWithdrawOrder(orderId, request), ConstructionOrderResponse.class);
    }

    private ConstructionOrderResponse doWithdrawOrder(long orderId, OrderActionRequest request) {
        ConstructionOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_ORDER_NOT_FOUND",
                        "施工单不存在: " + orderId));
        requireVersion(order, request.expectedVersion());
        if (order.status() != ConstructionOrderStatus.DRAFT) {
            throw ApiException.unprocessable("ORDER_NOT_WITHDRAWABLE",
                    "已生效施工单不得直接删除或撤回，当前状态=" + order.status());
        }
        Instant now = clock.instant();
        orderRepository.update(new ConstructionOrder(order.id(), order.runwayId(),
                ConstructionOrderStatus.WITHDRAWN, order.version(), order.evidence(), null, null, now,
                order.requestId(), order.createdAt(), now));
        return toResponse(orderRepository.findById(orderId).orElseThrow());
    }

    // ============================= 段验收（清场凭据） =============================

    /**
     * 提交清场凭据：同一施工段两个不同验收人各一份后段完成，仅消除本单该段闭锁；
     * 已结束施工或段已完成后的迟到凭据只读留痕，不改变状态、不能复活已中止巡检。requestId 幂等。
     */
    public ClearanceResponse submitClearance(long orderId, int segmentNo, ClearanceRequest request) {
        String fingerprint = StructuredFingerprint.sha256(SCOPE_CLEARANCE,
                request.requestId(), String.valueOf(orderId), String.valueOf(segmentNo), request.acceptor(),
                request.credentialRef(), request.submittedAt().toString());
        try {
            return idempotencyExecutor.execute(SCOPE_CLEARANCE, request.requestId(), fingerprint,
                    () -> doSubmitClearance(orderId, segmentNo, request), ClearanceResponse.class);
        } catch (DuplicateKeyException e) {
            ClearanceResponse replayed = idempotencyExecutor.tryReplay(SCOPE_CLEARANCE,
                    request.requestId(), fingerprint, ClearanceResponse.class);
            if (replayed != null) {
                return replayed;
            }
            if (isDuplicateAcceptor(e)) {
                throw ApiException.conflict("DUPLICATE_ACCEPTOR",
                        "验收人 " + request.acceptor() + " 已对该段提交过凭据，须两个不同验收人");
            }
            throw e;
        }
    }

    private ClearanceResponse doSubmitClearance(long orderId, int segmentNo, ClearanceRequest request) {
        ConstructionOrder probe = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_ORDER_NOT_FOUND",
                        "施工单不存在: " + orderId));
        // 与施工生效、恢复开放在同一跑道行锁上串行化：最后验收与新施工按事务次序判定。
        runwayLockRepository.lockRunway(probe.runwayId());
        ConstructionOrder order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        ConstructionSegment segment = segmentRepository.findByOrderIdAndSegmentNoForUpdate(orderId, segmentNo)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_SEGMENT_NOT_FOUND",
                        "施工段不存在: 单=" + orderId + " 段号=" + segmentNo));
        Instant now = clock.instant();
        if (order.status() == ConstructionOrderStatus.COMPLETED
                || segment.status() == ConstructionSegmentStatus.CLEARED) {
            // 迟到凭据：只读留痕，不改变段/单状态。
            ClearanceCredential late = credentialRepository.insert(new ClearanceCredential(0L, segment.id(),
                    orderId, request.acceptor(), request.credentialRef(), request.submittedAt(), true,
                    request.requestId(), now));
            return new ClearanceResponse(late.id(), orderId, segmentNo, true, segment.status().name(),
                    credentialRepository.countDistinctAcceptors(segment.id()), order.status().name(),
                    order.version());
        }
        if (order.status() != ConstructionOrderStatus.EFFECTIVE) {
            throw ApiException.unprocessable("ORDER_NOT_EFFECTIVE",
                    "施工单未生效或已撤回，不受理清场凭据，当前状态=" + order.status());
        }
        // 同一验收人重复提交由 uk_clearance_acceptor 唯一约束检测（含并发同键，冲突后重放幂等结果）。
        ClearanceCredential saved = credentialRepository.insert(new ClearanceCredential(0L, segment.id(),
                orderId, request.acceptor(), request.credentialRef(), request.submittedAt(), false,
                request.requestId(), now));
        int acceptors = credentialRepository.countDistinctAcceptors(segment.id());
        ConstructionSegment currentSegment = segment;
        ConstructionOrder currentOrder = order;
        if (acceptors >= REQUIRED_ACCEPTORS) {
            // 段完成：仅消除本单该段闭锁；重叠区域仍被其他段占用时仍不可用。
            segmentRepository.clearSegment(segment.id(), request.submittedAt(), now);
            currentSegment = segmentRepository.findById(segment.id()).orElseThrow();
            boolean allCleared = segmentRepository.findByOrderId(orderId).stream()
                    .allMatch(s -> s.status() == ConstructionSegmentStatus.CLEARED);
            if (allCleared) {
                orderRepository.update(new ConstructionOrder(order.id(), order.runwayId(),
                        ConstructionOrderStatus.COMPLETED, order.version(), order.evidence(),
                        order.activatedAt(), request.submittedAt(), null, order.requestId(),
                        order.createdAt(), now));
                currentOrder = orderRepository.findById(orderId).orElseThrow();
            }
        }
        return new ClearanceResponse(saved.id(), orderId, segmentNo, false, currentSegment.status().name(),
                acceptors, currentOrder.status().name(), currentOrder.version());
    }

    // ============================= 查询 =============================

    @Transactional(readOnly = true)
    public ConstructionOrderResponse getOrder(long orderId) {
        return toResponse(orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("CONSTRUCTION_ORDER_NOT_FOUND",
                        "施工单不存在: " + orderId)));
    }

    @Transactional(readOnly = true)
    public List<ConstructionOrderResponse> listOrders(String runwayId) {
        return orderRepository.findByRunway(runwayId).stream().map(this::toResponse).toList();
    }

    /**
     * 可用区域查询：所有活动施工区间（已生效单的 LOCKED 段）并集的补集；
     * 重叠不重复计长度，空可用返回空列表。未受控跑道无可用区域概念，返回404。
     */
    @Transactional(readOnly = true)
    public UsableSegmentsResponse getUsableSegments(String runwayId) {
        ControlledRunway runway = runwayRepository.findById(runwayId)
                .orElseThrow(() -> ApiException.notFound("RUNWAY_NOT_CONTROLLED",
                        "跑道未登记为受控跑道: " + runwayId));
        List<ConstructionSegment> locked = segmentRepository.findLockedByRunway(runwayId);
        List<UsableSegment> usable = complement(locked, runway.lengthMeters());
        int total = usable.stream().mapToInt(s -> s.endMeter() - s.startMeter()).sum();
        return new UsableSegmentsResponse(runwayId, runway.lengthMeters(), usable, total);
    }

    // ============================= 内部工具 =============================

    /**
     * 施工生效抢占：同一事务内取消该跑道“尚未开始”例行计划，已开始计划转中止并保留已完成检查项。
     * 被抢占计划置终态并释放获准位；抢占证据（计划清单）冻结在施工单上。
     */
    private PreemptionEvidence preemptRoutinePlans(ConstructionOrder order, Instant now) {
        List<RoutinePlan> activePlans = routinePlanRepository.findActiveByRunway(order.runwayId());
        List<Long> cancelled = new ArrayList<>();
        List<Long> aborted = new ArrayList<>();
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
                    plan.completedAt(), now, plan.requestId(), plan.createdAt(), now);
            routinePlanRepository.update(preempted);
            if (target == RoutinePlanStatus.ABORTED) {
                aborted.add(plan.id());
            } else {
                cancelled.add(plan.id());
            }
        }
        return new PreemptionEvidence(order.runwayId(), order.id(), cancelled, aborted, now);
    }

    /** 生效抢占证据：被取消/中止的例行计划清单，冻结在施工单上可复算。 */
    private record PreemptionEvidence(String runwayId, long orderId, List<Long> cancelledPlanIds,
                                      List<Long> abortedPlanIds, Instant activatedAt) {
    }

    /** 校验段集合：1至10个不同段号；0<=start<end<=长度；单内正长度重叠拒绝，端点相接允许。 */
    private void validateSegments(List<SegmentInput> segments, int lengthMeters) {
        if (segments == null || segments.isEmpty() || segments.size() > 10) {
            throw ApiException.badRequest("INVALID_SEGMENT_COUNT", "施工单须引用1至10个不同段号");
        }
        Set<Integer> seen = new HashSet<>();
        for (SegmentInput segment : segments) {
            if (!seen.add(segment.segmentNo())) {
                throw ApiException.unprocessable("DUPLICATE_SEGMENT_NO",
                        "同一施工单段号须不同，重复段号=" + segment.segmentNo());
            }
            if (segment.startMeter() < 0 || segment.startMeter() >= segment.endMeter()) {
                throw ApiException.badRequest("INVALID_SEGMENT_INTERVAL",
                        "施工段区间须满足0<=start<end: [" + segment.startMeter() + "," + segment.endMeter() + ")");
            }
            if (segment.endMeter() > lengthMeters) {
                throw ApiException.unprocessable("SEGMENT_EXCEEDS_RUNWAY_LENGTH",
                        "施工段终点 " + segment.endMeter() + " 超出跑道长度 " + lengthMeters);
            }
        }
        List<SegmentInput> sorted = segments.stream()
                .sorted(Comparator.comparingInt(SegmentInput::startMeter)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startMeter() < sorted.get(i - 1).endMeter()) {
                throw ApiException.unprocessable("SEGMENT_OVERLAP",
                        "同一施工单内部区间重叠，拒绝登记: [" + sorted.get(i - 1).startMeter() + ","
                                + sorted.get(i - 1).endMeter() + ") 与 [" + sorted.get(i).startMeter() + ","
                                + sorted.get(i).endMeter() + ")");
            }
        }
    }

    /** 活动施工区间并集的补集：半开区间、按起点升序；重叠不重复计长度。 */
    private List<UsableSegment> complement(List<ConstructionSegment> locked, int lengthMeters) {
        List<UsableSegment> usable = new ArrayList<>();
        int cursor = 0;
        for (ConstructionSegment segment : locked) {
            int start = Math.max(segment.startMeter(), cursor);
            if (start > cursor) {
                usable.add(new UsableSegment(cursor, start));
            }
            cursor = Math.max(cursor, segment.endMeter());
        }
        if (cursor < lengthMeters) {
            usable.add(new UsableSegment(cursor, lengthMeters));
        }
        return usable;
    }

    private void insertSegments(long orderId, List<SegmentInput> segments, Instant now) {
        for (SegmentInput input : segments) {
            segmentRepository.insert(new ConstructionSegment(0L, orderId, input.segmentNo(),
                    input.startMeter(), input.endMeter(), ConstructionSegmentStatus.PENDING, null, now, now));
        }
    }

    private List<List<String>> segmentFields(List<SegmentInput> segments) {
        if (segments == null) {
            return List.of();
        }
        return segments.stream()
                .map(s -> List.of(String.valueOf(s.segmentNo()), String.valueOf(s.startMeter()),
                        String.valueOf(s.endMeter())))
                .toList();
    }

    private void requireVersion(ConstructionOrder order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("ORDER_VERSION_CONFLICT",
                    "施工单版本已变更，当前=" + order.version() + "，expectedVersion=" + expectedVersion);
        }
    }

    /**
     * 幂等执行：并发同键冲突时原事务回滚后尝试重放（同参返回首次结果，异参409）。
     */
    private <T> T executeIdempotent(String scope, String requestId, String fingerprint,
                                    java.util.function.Supplier<T> action, Class<T> type) {
        try {
            return idempotencyExecutor.execute(scope, requestId, fingerprint, action, type);
        } catch (DuplicateKeyException e) {
            T replayed = idempotencyExecutor.tryReplay(scope, requestId, fingerprint, type);
            if (replayed != null) {
                return replayed;
            }
            throw e;
        }
    }

    private RunwayResponse toResponse(ControlledRunway runway) {
        return new RunwayResponse(runway.runwayId(), runway.lengthMeters(), runway.version(),
                runway.createdAt(), runway.updatedAt());
    }

    private ConstructionOrderResponse toResponse(ConstructionOrder order) {
        List<SegmentView> segments = segmentRepository.findByOrderId(order.id()).stream()
                .map(s -> new SegmentView(s.id(), s.segmentNo(), s.startMeter(), s.endMeter(),
                        s.status().name(), s.clearedAt(),
                        credentialRepository.countDistinctAcceptors(s.id())))
                .toList();
        return new ConstructionOrderResponse(order.id(), order.runwayId(), order.status().name(),
                order.version(), segments, order.activatedAt(), order.completedAt(), order.withdrawnAt(),
                order.evidence());
    }

    private String writeEvidence(PreemptionEvidence evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("抢占证据序列化失败", e);
        }
    }

    private boolean isControlledRunwayConflict(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("controlled_runway");
    }

    private boolean isDuplicateAcceptor(DuplicateKeyException e) {
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("uk_clearance_acceptor");
    }
}
