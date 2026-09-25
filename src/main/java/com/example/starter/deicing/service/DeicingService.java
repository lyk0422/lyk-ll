package com.example.starter.deicing.service;

import com.example.starter.deicing.api.ApplyRequest;
import com.example.starter.deicing.api.AssignmentView;
import com.example.starter.deicing.api.CancelRequest;
import com.example.starter.deicing.api.DelayRequest;
import com.example.starter.deicing.api.DepartRequest;
import com.example.starter.deicing.api.QueueItemResponse;
import com.example.starter.deicing.api.SnapshotResponse;
import com.example.starter.deicing.engine.SchedulingEngine;
import com.example.starter.deicing.exception.ApiException;
import com.example.starter.deicing.model.OperationType;
import com.example.starter.deicing.model.QueueStatus;
import com.example.starter.deicing.po.AssignmentPO;
import com.example.starter.deicing.po.IdempotentOperationPO;
import com.example.starter.deicing.po.QueueRequestPO;
import com.example.starter.deicing.po.TimetablePO;
import com.example.starter.deicing.repository.AssignmentRepository;
import com.example.starter.deicing.repository.IdempotencyRepository;
import com.example.starter.deicing.repository.QueueRepository;
import com.example.starter.deicing.repository.TimetableRepository;
import com.example.starter.deicing.support.Fingerprints;
import com.example.starter.deicing.support.ServiceClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 除冰排队与起飞时刻保护领域服务。
 *
 * <p>关键规则：</p>
 * <ul>
 *   <li>申请（APPLY）与出队确认（DISPATCH）及起飞/取消以 requestId 幂等，
 *       指纹覆盖 requestId 与全部业务字段；同键异参 409 且不改变队列。</li>
 *   <li>同一航班只允许一条非终态记录，由 flight_active 唯一约束保证并发唯一受理。</li>
 *   <li>出队优先级：截止时刻（起飞时刻减除冰时长）早者优先；同刻到达按申请序号定序。</li>
 *   <li>两步法须在 HOT 内起飞：实际起飞恰在失效终点仍有效，超过则 HOT 失效须重新除冰。</li>
 *   <li>作业开始后占用锁定不可插队；前序延误致 HOT 不足主动置 HOT 风险并给出最早可重排时刻。</li>
 *   <li>终态操作幂等成功；迟到起飞回报不复活已取消队列项。</li>
 * </ul>
 *
 * <p>幂等实现：业务执行前先在 idempotent_operation 抢占一行 PENDING 台账（主键唯一），
 * 业务完成后回填首次响应；并发重复操作在抢占处失败，事务回滚，绝不重复排定。</p>
 */
@Service
public class DeicingService {

    /** 台账响应体占位值：已抢占受理但业务尚未完成（仅在事务进行中可见）。 */
    private static final String PENDING_BODY = "";

    private final QueueRepository queueRepository;
    private final AssignmentRepository assignmentRepository;
    private final TimetableRepository timetableRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final SchedulingEngine schedulingEngine;
    private final ServiceClock clock;
    private final ObjectMapper objectMapper;

    /**
     * @param queueRepository        排队申请仓储
     * @param assignmentRepository   占用仓储
     * @param timetableRepository    时刻表仓储
     * @param idempotencyRepository  幂等台账仓储
     * @param schedulingEngine       排定引擎
     * @param clock                  可控时钟
     * @param objectMapper           JSON 序列化（保存首次响应）
     */
    public DeicingService(QueueRepository queueRepository,
                          AssignmentRepository assignmentRepository,
                          TimetableRepository timetableRepository,
                          IdempotencyRepository idempotencyRepository,
                          SchedulingEngine schedulingEngine,
                          ServiceClock clock,
                          ObjectMapper objectMapper) {
        this.queueRepository = queueRepository;
        this.assignmentRepository = assignmentRepository;
        this.timetableRepository = timetableRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.schedulingEngine = schedulingEngine;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 携带 HTTP 状态的服务结果：重放时返回首次受理的状态码与响应体。
     *
     * @param httpStatus 首次或本次 HTTP 状态
     * @param view       队列记录视图
     */
    public record IdentifiedResult(int httpStatus, QueueItemResponse view) {
    }

    /**
     * 申请除冰。
     *
     * @param request 申请体
     * @return 201 新建；同键同参重放返回首次 201 结果
     */
    @Transactional
    public IdentifiedResult apply(ApplyRequest request) {
        long now = clock.nowMillis();
        TimetablePO timetable = timetableRepository.findByType(request.deiceType());
        if (timetable == null) {
            throw ApiException.badRequest("TIMETABLE_MISSING",
                    "时刻表缺少除冰类型: " + request.deiceType());
        }
        long durationMs = timetable.getDurationMin() * 60_000L;
        List<String> bays = timetableRepository.findActiveBayIds();
        List<String> trucks = timetableRepository.findActiveTruckIds();
        if (request.requiredBays() > bays.size() || request.requiredBays() > trucks.size()) {
            throw ApiException.badRequest("INSUFFICIENT_RESOURCES",
                    "所需车位数超过可用车位或除冰车数量");
        }
        if (request.targetDepartureTs() <= now) {
            throw ApiException.badRequest("DEPARTURE_IN_PAST", "目标起飞时刻必须晚于当前时刻");
        }

        String fingerprint = Fingerprints.sha256(
                request.requestId(), request.flightNo(), request.stand(),
                request.deiceType().name(), Integer.toString(request.requiredBays()),
                Long.toString(request.targetDepartureTs()));
        IdentifiedResult replay = claimOrReplay(request.requestId(), OperationType.APPLY, fingerprint);
        if (replay != null) {
            return replay;
        }

        QueueRequestPO po = new QueueRequestPO();
        po.setRequestId(request.requestId());
        po.setFlightNo(request.flightNo());
        po.setStand(request.stand());
        po.setDeiceType(request.deiceType());
        po.setRequiredBays(request.requiredBays());
        po.setTargetDepartureTs(request.targetDepartureTs());
        po.setExpectedDepartureTs(request.targetDepartureTs());
        po.setDeadlineTs(request.targetDepartureTs() - durationMs);
        po.setStatus(QueueStatus.WAITING);
        po.setRoundNo(1);
        po.setCreatedTs(now);
        po.setUpdatedTs(now);
        queueRepository.insert(po);
        if (!queueRepository.registerFlightActive(
                request.flightNo(), request.requestId(), now)) {
            // 同一航班已有进行中记录：回滚本次申请（含抢占的台账行）
            throw ApiException.conflict("FLIGHT_ALREADY_ACTIVE",
                    "航班 " + request.flightNo() + " 已存在进行中的除冰队列记录");
        }

        QueueItemResponse view = toView(po);
        completeOutcome(request.requestId(), OperationType.APPLY, 201, view);
        return new IdentifiedResult(201, view);
    }

    /**
     * 出队确认：仅允许确认当前队首（WAITING 中优先级最高者），排定半开区间占用。
     *
     * @param requestId 申请号
     * @return 排定后的记录视图
     */
    @Transactional
    public IdentifiedResult dispatch(String requestId) {
        String fingerprint = Fingerprints.sha256(requestId);
        IdentifiedResult replay = claimOrReplay(requestId, OperationType.DISPATCH, fingerprint);
        if (replay != null) {
            return replay;
        }

        QueueRequestPO po = requireRequest(requestId);
        if (po.getStatus() != QueueStatus.WAITING) {
            throw ApiException.conflict("NOT_WAITING",
                    "当前状态不允许出队确认: " + po.getStatus());
        }
        QueueRequestPO head = queueRepository.findAll().stream()
                .filter(r -> r.getStatus() == QueueStatus.WAITING)
                .min(QUEUE_PRIORITY)
                .orElseThrow(() -> ApiException.conflict("QUEUE_EMPTY", "队列为空"));
        if (!head.getRequestId().equals(requestId)) {
            throw ApiException.conflict("NOT_QUEUE_HEAD",
                    "该申请不是当前队首，不能越过优先级更高的申请出队；队首: " + head.getRequestId());
        }

        TimetablePO timetable = requireTimetable(po);
        long durationMs = timetable.getDurationMin() * 60_000L;
        long now = clock.nowMillis();
        SchedulingEngine.SchedulePlan plan = scheduleEarliest(po, durationMs, now);
        for (AssignmentPO assignment : plan.assignments()) {
            assignmentRepository.insert(assignment);
        }
        po.setStatus(QueueStatus.SCHEDULED);
        po.setUpdatedTs(now);
        queueRepository.save(po);

        QueueItemResponse view = toView(po);
        completeOutcome(requestId, OperationType.DISPATCH, 200, view);
        return new IdentifiedResult(200, view);
    }

    /**
     * 作业开始：占用转 ACTIVE 并锁定，不可被插队。
     *
     * @param requestId 申请号
     * @return 记录视图
     */
    @Transactional
    public QueueItemResponse startWork(String requestId) {
        QueueRequestPO po = requireRequest(requestId);
        long now = clock.nowMillis();
        if (po.getStatus() == QueueStatus.IN_PROGRESS) {
            return toView(po);
        }
        if (po.getStatus() != QueueStatus.SCHEDULED) {
            throw ApiException.conflict("NOT_SCHEDULED",
                    "仅已排定（SCHEDULED）的记录可以开始作业，当前状态: " + po.getStatus());
        }
        assignmentRepository.markActive(requestId, po.getRoundNo());
        po.setStatus(QueueStatus.IN_PROGRESS);
        po.setActualStartTs(now);
        po.setUpdatedTs(now);
        queueRepository.save(po);
        return toView(po);
    }

    /**
     * 作业完成：两步法据此计算 HOT 失效终点并即时评估风险。
     *
     * @param requestId 申请号
     * @return 记录视图（可能已被主动置为 HOT_RISK）
     */
    @Transactional
    public QueueItemResponse completeWork(String requestId) {
        QueueRequestPO po = requireRequest(requestId);
        long now = clock.nowMillis();
        if (po.getStatus() == QueueStatus.DEICED || po.getStatus() == QueueStatus.HOT_RISK) {
            return toView(po);
        }
        if (po.getStatus() != QueueStatus.IN_PROGRESS) {
            throw ApiException.conflict("NOT_IN_PROGRESS",
                    "仅作业中（IN_PROGRESS）的记录可以完成作业，当前状态: " + po.getStatus());
        }
        assignmentRepository.markDone(requestId, po.getRoundNo(), now);
        po.setStatus(QueueStatus.DEICED);
        po.setActualFinishTs(now);
        TimetablePO timetable = requireTimetable(po);
        if (timetable.getHotMin() != null) {
            po.setHotExpiresTs(now + timetable.getHotMin() * 60_000L);
        }
        po.setUpdatedTs(now);
        evaluateHotRisk(po, now);
        queueRepository.save(po);
        return toView(po);
    }

    /**
     * 前序延误回报：更新最新预计起飞时刻；若两步法 HOT 不足主动置风险。
     * 相同内容重复回报结果相同（幂等）；新时刻为新事实直接覆盖。
     *
     * @param request 延误回报
     * @return 记录视图
     */
    @Transactional
    public QueueItemResponse reportDelay(DelayRequest request) {
        QueueRequestPO po = requireRequest(request.requestId());
        long now = clock.nowMillis();
        if (po.getStatus().isTerminal()) {
            throw ApiException.conflict("ALREADY_TERMINAL",
                    "终态记录不再接受延误回报: " + po.getStatus());
        }
        if (request.expectedDepartureTs() < po.getTargetDepartureTs()) {
            throw ApiException.badRequest("DELAY_BEFORE_TARGET",
                    "延误后的预计起飞不得早于目标起飞时刻");
        }
        po.setExpectedDepartureTs(request.expectedDepartureTs());
        po.setUpdatedTs(now);
        evaluateHotRisk(po, now);
        queueRepository.save(po);
        return toView(po);
    }

    /**
     * HOT 风险后确认重新除冰：开启新一轮排定（历史轮次占用保留为事实）。
     *
     * @param requestId 申请号
     * @return 已重新排定的记录视图
     */
    @Transactional
    public QueueItemResponse reschedule(String requestId) {
        QueueRequestPO po = requireRequest(requestId);
        long now = clock.nowMillis();
        if (po.getStatus() != QueueStatus.HOT_RISK) {
            throw ApiException.conflict("NOT_HOT_RISK",
                    "仅 HOT 风险（HOT_RISK）记录可以确认重新除冰，当前状态: " + po.getStatus());
        }
        TimetablePO timetable = requireTimetable(po);
        long durationMs = timetable.getDurationMin() * 60_000L;
        long earliest = Math.max(now,
                po.getActualFinishTs() == null ? now : po.getActualFinishTs());
        if (po.getEarliestReschedTs() != null) {
            earliest = Math.max(earliest, po.getEarliestReschedTs());
        }
        int newRound = po.getRoundNo() + 1;
        SchedulingEngine.SchedulePlan plan = scheduleEarliest(po, durationMs, earliest, newRound);
        for (AssignmentPO assignment : plan.assignments()) {
            assignmentRepository.insert(assignment);
        }
        po.setRoundNo(newRound);
        po.setStatus(QueueStatus.SCHEDULED);
        po.setActualStartTs(null);
        po.setActualFinishTs(null);
        po.setHotExpiresTs(null);
        po.setEarliestReschedTs(null);
        po.setUpdatedTs(now);
        queueRepository.save(po);
        return toView(po);
    }

    /**
     * 起飞回报与 HOT 判定。
     *
     * <p>两步法实际起飞恰在失效终点有效；超过终点判 HOT_EXPIRED（终态，须重新申请除冰）。
     * 已取消记录上的迟到起飞回报原样返回取消态，绝不复活。重复终态回报幂等成功。</p>
     *
     * @param request 起飞回报
     * @return 判定后的记录视图
     */
    @Transactional
    public IdentifiedResult depart(DepartRequest request) {
        String fingerprint = Fingerprints.sha256(
                request.requestId(), Long.toString(request.actualDepartureTs()));

        QueueRequestPO po = requireRequest(request.requestId());

        // 任何终态上的起飞回报（含同参重放与异参迟到回报）一律幂等成功返回终态，绝不复活
        if (po.getStatus().isTerminal()) {
            return new IdentifiedResult(200, toView(po));
        }
        IdempotentOperationPO ledger = idempotencyRepository.find(
                request.requestId(), OperationType.DEPART.name());
        if (ledger != null && !PENDING_BODY.equals(ledger.getResponseBody())) {
            // 非终态但已有完成的起飞判定台账：同参重放首次结果，异参 409
            return replayOrConflict(ledger, fingerprint);
        }
        if (po.getStatus() != QueueStatus.DEICED && po.getStatus() != QueueStatus.HOT_RISK) {
            throw ApiException.conflict("NOT_DEICED",
                    "除冰尚未完成，不能回报起飞，当前状态: " + po.getStatus());
        }
        IdentifiedResult replay = claimOrReplay(request.requestId(), OperationType.DEPART, fingerprint);
        if (replay != null) {
            return replay;
        }

        boolean withinHot;
        if (po.getHotExpiresTs() == null) {
            // 一步法无 HOT 约束
            withinHot = true;
        } else {
            // 恰在 HOT 终点视为有效（半开语义：<=）
            withinHot = request.actualDepartureTs() <= po.getHotExpiresTs();
        }

        long now = clock.nowMillis();
        if (withinHot) {
            po.setStatus(QueueStatus.DEPARTED);
        } else {
            po.setStatus(QueueStatus.HOT_EXPIRED);
        }
        po.setEarliestReschedTs(null);
        po.setUpdatedTs(now);
        queueRepository.save(po);
        queueRepository.unregisterFlightActive(po.getFlightNo());

        QueueItemResponse view = toView(po);
        completeOutcome(request.requestId(), OperationType.DEPART, 200, view);
        return new IdentifiedResult(200, view);
    }

    /**
     * 取消；重复取消幂等成功。作业已开始不可取消。
     *
     * @param request 取消请求
     * @return 记录视图
     */
    @Transactional
    public IdentifiedResult cancel(CancelRequest request) {
        QueueRequestPO po = requireRequest(request.requestId());
        if (po.getStatus().isTerminal()) {
            // 任何终态上的重复/迟到取消均幂等成功，不改写历史
            return new IdentifiedResult(200, toView(po));
        }
        if (po.getStatus() == QueueStatus.IN_PROGRESS) {
            throw ApiException.conflict("WORK_IN_PROGRESS",
                    "作业已经开始，不可取消（占用锁定中）");
        }

        String reason = request.reason() == null ? "" : request.reason();
        String fingerprint = Fingerprints.sha256(request.requestId(), reason);
        IdentifiedResult replay = claimOrReplay(request.requestId(), OperationType.CANCEL, fingerprint);
        if (replay != null) {
            return replay;
        }

        long now = clock.nowMillis();
        if (po.getStatus() == QueueStatus.SCHEDULED) {
            assignmentRepository.releaseReserved(request.requestId(), po.getRoundNo());
        }
        po.setStatus(QueueStatus.CANCELLED);
        po.setCancelReason(request.reason());
        po.setUpdatedTs(now);
        queueRepository.save(po);
        queueRepository.unregisterFlightActive(po.getFlightNo());

        QueueItemResponse view = toView(po);
        completeOutcome(request.requestId(), OperationType.CANCEL, 200, view);
        return new IdentifiedResult(200, view);
    }

    /**
     * @param requestId 申请号
     * @return 单条记录视图
     */
    @Transactional(readOnly = true)
    public QueueItemResponse get(String requestId) {
        QueueRequestPO po = requireRequest(requestId);
        return toView(po);
    }

    /**
     * @return 队列快照：非终态按出队优先级排列，终态按更新时刻倒序、序号兜底，重复查询稳定
     */
    @Transactional(readOnly = true)
    public SnapshotResponse snapshot() {
        List<QueueRequestPO> all = queueRepository.findAll();
        List<QueueItemResponse> active = all.stream()
                .filter(r -> !r.getStatus().isTerminal())
                .sorted(QUEUE_PRIORITY)
                .map(this::toView)
                .toList();
        List<QueueItemResponse> terminal = all.stream()
                .filter(r -> r.getStatus().isTerminal())
                .sorted(Comparator.comparingLong(QueueRequestPO::getUpdatedTs).reversed()
                        .thenComparingLong(QueueRequestPO::getSeq))
                .map(this::toView)
                .toList();
        return new SnapshotResponse(clock.nowMillis(),
                java.util.stream.Stream.concat(active.stream(), terminal.stream()).toList());
    }

    /**
     * 车位占用时间线（半开区间，可按车位与时间窗过滤）。
     *
     * @param bayId  车位过滤，可空
     * @param fromTs 窗口起点（含），可空
     * @param toTs   窗口终点（不含），可空
     * @return 占用视图
     */
    @Transactional(readOnly = true)
    public List<AssignmentView> timeline(String bayId, Long fromTs, Long toTs) {
        return assignmentRepository.findAllEffective().stream()
                .filter(a -> bayId == null || bayId.isBlank() || bayId.equals(a.getBayId()))
                .filter(a -> fromTs == null || a.getEndTs() > fromTs)
                .filter(a -> toTs == null || a.getStartTs() < toTs)
                .sorted(Comparator.comparingLong(AssignmentPO::getStartTs)
                        .thenComparing(AssignmentPO::getBayId)
                        .thenComparing(AssignmentPO::getTruckId)
                        .thenComparingLong(AssignmentPO::getId))
                .map(a -> new AssignmentView(a.getRequestId(), a.getRoundNo(), a.getBayId(),
                        a.getTruckId(), a.getStartTs(), a.getEndTs(), a.getState()))
                .toList();
    }

    /** 出队优先级：截止时刻早者优先；同刻到达按申请时刻、申请序号定序。 */
    private static final Comparator<QueueRequestPO> QUEUE_PRIORITY =
            Comparator.comparingLong(QueueRequestPO::getDeadlineTs)
                    .thenComparingLong(QueueRequestPO::getCreatedTs)
                    .thenComparingLong(QueueRequestPO::getSeq);

    /**
     * 抢占幂等受理。
     * <ul>
     *   <li>无台账：插入 PENDING 占位并返回 null（本次调用继续执行业务，完成后回填）；</li>
     *   <li>已完成台账：指纹一致返回首次结果用于重放，异参抛 409（不改变队列）；</li>
     *   <li>PENDING 台账：并发重复，同参/异参均抛 409，业务绝不重复执行。</li>
     * </ul>
     * 业务后续失败时事务回滚，PENDING 行随之撤销，不影响重试。
     *
     * @param requestId   申请号
     * @param operation   操作类型
     * @param fingerprint 本次请求指纹
     * @return 已完成时的首次结果；本次抢占成功返回 null
     */
    private IdentifiedResult claimOrReplay(String requestId, OperationType operation,
                                           String fingerprint) {
        IdempotentOperationPO existing = idempotencyRepository.find(requestId, operation.name());
        if (existing != null) {
            if (!existing.getFingerprint().equals(fingerprint)) {
                throw ApiException.idempotencyConflict(
                        "同一 requestId 的业务参数与首次请求不一致，拒绝执行且不改变队列");
            }
            if (PENDING_BODY.equals(existing.getResponseBody())) {
                throw ApiException.conflict("CONCURRENT_OPERATION",
                        "同一操作正在并发处理，已由首次请求受理");
            }
            return replay(existing);
        }        IdempotentOperationPO pending = new IdempotentOperationPO();
        pending.setRequestId(requestId);
        pending.setOperation(operation.name());
        pending.setFingerprint(fingerprint);
        pending.setResponseStatus(0);
        pending.setResponseBody(PENDING_BODY);
        pending.setCreatedTs(clock.nowMillis());
        try {
            idempotencyRepository.insert(pending);
        } catch (DuplicateKeyException e) {
            IdempotentOperationPO winner = idempotencyRepository.find(requestId, operation.name());
            if (winner == null) {
                throw e;
            }
            if (!winner.getFingerprint().equals(fingerprint)) {
                throw ApiException.idempotencyConflict(
                        "同一 requestId 的业务参数与首次请求不一致，拒绝执行且不改变队列");
            }
            if (PENDING_BODY.equals(winner.getResponseBody())) {
                throw ApiException.conflict("CONCURRENT_OPERATION",
                        "同一操作正在并发处理，已由首次请求受理");
            }
            // 并发中抢到的线程已提交：重放其首次结果
            return replay(winner);
        }
        return null;
    }

    /**
     * 业务完成后回填首次响应。
     *
     * @param requestId   申请号
     * @param operation   操作类型
     * @param httpStatus  首次响应状态码
     * @param view        首次响应视图
     */
    private void completeOutcome(String requestId, OperationType operation, int httpStatus,
                                 QueueItemResponse view) {
        String body;
        try {
            body = objectMapper.writeValueAsString(view);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
        idempotencyRepository.updateOutcome(requestId, operation.name(), httpStatus, body);
    }

    /**
     * 已完成台账重放：指纹一致时反序列化首次响应。
     *
     * @param ledger      台账行
     * @param fingerprint 本次指纹
     * @return 首次结果
     */
    private IdentifiedResult replayOrConflict(IdempotentOperationPO ledger, String fingerprint) {
        if (!ledger.getFingerprint().equals(fingerprint)) {
            throw ApiException.idempotencyConflict(
                    "同一 requestId 的业务参数与首次请求不一致，拒绝执行且不改变队列");
        }
        return replay(ledger);
    }

    /**
     * 反序列化首次受理响应。
     *
     * @param ledger 已完成台账行
     * @return 首次结果
     */
    private IdentifiedResult replay(IdempotentOperationPO ledger) {
        try {
            QueueItemResponse view = objectMapper.readValue(
                    ledger.getResponseBody(), QueueItemResponse.class);
            return new IdentifiedResult(ledger.getResponseStatus(), view);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 以当前时刻为最早起点排定。
     */
    private SchedulingEngine.SchedulePlan scheduleEarliest(QueueRequestPO po, long durationMs,
                                                            long earliestStart) {
        return scheduleEarliest(po, durationMs, earliestStart, po.getRoundNo());
    }

    /**
     * 调用引擎计算最早可行窗口。
     */
    private SchedulingEngine.SchedulePlan scheduleEarliest(QueueRequestPO po, long durationMs,
                                                           long earliestStart, int roundNo) {
        return schedulingEngine.plan(po.getRequestId(), roundNo, po.getRequiredBays(), durationMs,
                earliestStart, timetableRepository.findActiveBayIds(),
                timetableRepository.findActiveTruckIds(),
                assignmentRepository.findAllEffective());
    }

    /**
     * 两步法 HOT 评估：预计起飞超过失效终点时主动置 HOT 风险并计算最早可重排时刻；
     * 预计时刻回落到 HOT 内则风险解除。一步法或尚未完成除冰时不评估。
     *
     * @param po  记录
     * @param now 当前时刻
     */
    private void evaluateHotRisk(QueueRequestPO po, long now) {
        if (po.getHotExpiresTs() == null) {
            return;
        }
        if (po.getExpectedDepartureTs() <= po.getHotExpiresTs()) {
            if (po.getStatus() == QueueStatus.HOT_RISK) {
                po.setStatus(QueueStatus.DEICED);
                po.setEarliestReschedTs(null);
            }
            return;
        }
        TimetablePO timetable = requireTimetable(po);
        long durationMs = timetable.getDurationMin() * 60_000L;
        long earliest = Math.max(now,
                po.getActualFinishTs() == null ? now : po.getActualFinishTs());
        SchedulingEngine.SchedulePlan plan = schedulingEngine.plan(
                po.getRequestId(), po.getRoundNo() + 1, po.getRequiredBays(), durationMs,
                earliest, timetableRepository.findActiveBayIds(),
                timetableRepository.findActiveTruckIds(),
                assignmentRepository.findAllEffective());
        po.setStatus(QueueStatus.HOT_RISK);
        po.setEarliestReschedTs(plan.startTs());
    }

    /**
     * @param requestId 申请号
     * @return 记录；不存在抛 404
     */
    private QueueRequestPO requireRequest(String requestId) {
        QueueRequestPO po = queueRepository.findByRequestId(requestId);
        if (po == null) {
            throw ApiException.notFound("申请不存在: " + requestId);
        }
        return po;
    }

    /**
     * @param po 记录
     * @return 时刻表
     */
    private TimetablePO requireTimetable(QueueRequestPO po) {
        TimetablePO timetable = timetableRepository.findByType(po.getDeiceType());
        if (timetable == null) {
            throw ApiException.badRequest("TIMETABLE_MISSING",
                    "时刻表缺少除冰类型: " + po.getDeiceType());
        }
        return timetable;
    }

    /**
     * @param po 记录
     * @return 含全部轮次占用时间线的视图
     */
    private QueueItemResponse toView(QueueRequestPO po) {
        List<AssignmentView> assignments = assignmentRepository.findByRequestId(po.getRequestId())
                .stream()
                .map(a -> new AssignmentView(a.getRequestId(), a.getRoundNo(), a.getBayId(),
                        a.getTruckId(), a.getStartTs(), a.getEndTs(), a.getState()))
                .toList();
        return new QueueItemResponse(
                po.getRequestId(), po.getSeq(), po.getFlightNo(), po.getStand(),
                po.getDeiceType(), po.getRequiredBays(), po.getTargetDepartureTs(),
                po.getExpectedDepartureTs(), po.getDeadlineTs(), po.getStatus(),
                po.getRoundNo(),
                po.getActualStartTs(), po.getActualFinishTs(), po.getHotExpiresTs(),
                po.getEarliestReschedTs(), po.getCancelReason(), assignments);
    }
}
