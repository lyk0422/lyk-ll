package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.api.ApplicationRequest;
import com.example.starter.api.CloseRequest;
import com.example.starter.api.CloseResponse;
import com.example.starter.api.ExitRequest;
import com.example.starter.api.ExitResponse;
import com.example.starter.api.OperationDetailResponse;
import com.example.starter.api.PlanChangeRequest;
import com.example.starter.api.PlanProfileStep;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.SegmentRequest;
import com.example.starter.api.SegmentResponse;
import com.example.starter.api.VitalsRequest;
import com.example.starter.api.VitalsResponse;
import com.example.starter.domain.DivePhase;
import com.example.starter.domain.OperationStatus;
import com.example.starter.repo.DivingRepository;
import com.example.starter.repo.IdempotentRow;
import com.example.starter.repo.OperationRow;
import com.example.starter.repo.PlanRow;
import com.example.starter.repo.SegmentRow;
import com.example.starter.support.BusinessException;
import com.example.starter.support.DiveRules;
import com.example.starter.support.Fingerprints;
import com.example.starter.support.PlanCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 潜水作业主服务。潜次流转：作业申请 → 减压方案 → 潜水执行 → 出水观察 → 结案。
 *
 * <p>所有写接口以 requestId 幂等（指纹覆盖 requestId、请求类型与全部业务字段）：
 * 同键同参返回首次结果，同键异参返回 409 且不改变任何潜次状态。
 */
@Service
public class DivingService {

    private static final String KIND_APPLICATION = "APPLICATION";
    private static final String KIND_PLAN_CHANGE = "PLAN_CHANGE";
    private static final String KIND_SEGMENT = "SEGMENT";
    private static final String KIND_EXIT = "EXIT";
    private static final String KIND_VITALS = "VITALS";
    private static final String KIND_CLOSE = "CLOSE";

    private final DivingRepository repository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public DivingService(DivingRepository repository, Clock clock, ObjectMapper objectMapper) {
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** service 返回包装：body 为响应体，httpStatus 为首次响应状态，replay 标识幂等重放。 */
    public record ServiceResult<T>(T body, int httpStatus, boolean replay) {
    }

    // ================= 作业申请 =================

    @Transactional
    public ServiceResult<PlanResponse> apply(ApplicationRequest request) {
        String fingerprint = fingerprint(KIND_APPLICATION, request.requestId(),
                request.diverA(), request.diverB(), request.depthM(), request.bottomMinutes());
        IdempotentRow gate = beginIdempotency(KIND_APPLICATION, request.requestId(), fingerprint);
        if (gate != null) {
            return replay(gate, PlanResponse.class);
        }
        if (request.diverA().equals(request.diverB())) {
            throw BusinessException.badRequest("SAME_DIVER", "两人一组必须由两名不同潜水员组成");
        }
        int[] deco = lookupDeco(request.depthM());
        int ndlMinutes = deco[0];
        boolean safetyStop = deco[1] == 1;
        if (request.bottomMinutes() > ndlMinutes) {
            throw BusinessException.unprocessable("NDL_EXCEEDED",
                    "计划水底 " + request.bottomMinutes() + " 分钟超过免减压极限 " + ndlMinutes + " 分钟");
        }
        long now = clock.instant().getEpochSecond();
        String groupKey = groupKey(request.diverA(), request.diverB());
        String operationNo = newOperationNo(now);
        OperationRow op = new OperationRow(null, operationNo, request.requestId(),
                request.diverA(), request.diverB(), groupKey,
                request.depthM(), request.bottomMinutes(), OperationStatus.PLANNED.name(),
                1, null, null, null, null, now);
        long operationId = repository.insertOperation(op);
        if (!repository.occupyGroup(groupKey, operationId, operationNo, now)) {
            throw BusinessException.conflict("GROUP_BUSY",
                    "同一潜水组同时刻只允许一个进行中潜次");
        }
        boolean atLimit = request.bottomMinutes() == ndlMinutes;
        List<PlanProfileStep> profile = PlanCalculator.profile(
                request.depthM(), request.bottomMinutes(), ndlMinutes, safetyStop);
        repository.insertPlan(new PlanRow(null, operationId, 1, request.depthM(),
                request.bottomMinutes(), ndlMinutes, atLimit, writeJson(profile), true, now));
        repository.insertEvent(operationId, "PLAN_CREATED",
                "申请受理，生成 v1 方案，深度 " + request.depthM() + " 米，水底 "
                        + request.bottomMinutes() + " 分钟" + (atLimit ? "（恰免减压极限）" : ""), now);
        PlanResponse response = new PlanResponse(operationNo, request.requestId(), 1,
                request.depthM(), request.bottomMinutes(), ndlMinutes, atLimit, safetyStop,
                OperationStatus.PLANNED.name(), profile, 0);
        finishIdempotency(request.requestId(), 201, response);
        return new ServiceResult<>(response, 201, false);
    }

    // ================= 方案变更 =================

    @Transactional
    public ServiceResult<PlanResponse> changePlan(PlanChangeRequest request) {
        String fingerprint = fingerprint(KIND_PLAN_CHANGE, request.requestId(),
                request.operationNo(), request.depthM(), request.bottomMinutes());
        IdempotentRow gate = beginIdempotency(KIND_PLAN_CHANGE, request.requestId(), fingerprint);
        if (gate != null) {
            return replay(gate, PlanResponse.class);
        }
        OperationRow op = requireOperation(request.operationNo());
        if (OperationStatus.CLOSED.name().equals(op.status())
                || OperationStatus.CHAMBER.name().equals(op.status())
                || OperationStatus.OBSERVING.name().equals(op.status())) {
            throw BusinessException.unprocessable("PLAN_CHANGE_NOT_ALLOWED",
                    "潜次已进入 " + op.status() + "，方案不可变更");
        }
        int[] deco = lookupDeco(request.depthM());
        int ndlMinutes = deco[0];
        boolean safetyStop = deco[1] == 1;
        if (request.bottomMinutes() > ndlMinutes) {
            throw BusinessException.unprocessable("NDL_EXCEEDED",
                    "计划水底 " + request.bottomMinutes() + " 分钟超过免减压极限 " + ndlMinutes + " 分钟");
        }
        long now = clock.instant().getEpochSecond();
        int oldVersion = op.planVersion();
        // 同一事务：旧方案停用、旧版本记录全部冻结、配对组与台账删除，随后新方案生效。
        repository.deactivatePlans(op.id());
        int frozenCount = repository.freezeSegments(op.id(), oldVersion);
        repository.deleteLedgerByFrozenSegments(op.id(), oldVersion);
        int newVersion = oldVersion + 1;
        boolean atLimit = request.bottomMinutes() == ndlMinutes;
        List<PlanProfileStep> profile = PlanCalculator.profile(
                request.depthM(), request.bottomMinutes(), ndlMinutes, safetyStop);
        repository.insertPlan(new PlanRow(null, op.id(), newVersion, request.depthM(),
                request.bottomMinutes(), ndlMinutes, atLimit, writeJson(profile), true, now));
        repository.updateOperationPlan(op.id(), request.depthM(), request.bottomMinutes(), newVersion);
        repository.insertEvent(op.id(), "PLAN_CHANGED",
                "方案 v" + oldVersion + " 变更为 v" + newVersion + "，同事务冻结旧方案记录 "
                        + frozenCount + " 条", now);
        PlanResponse response = new PlanResponse(op.operationNo(), request.requestId(), newVersion,
                request.depthM(), request.bottomMinutes(), ndlMinutes, atLimit, safetyStop,
                op.status(), profile, frozenCount);
        finishIdempotency(request.requestId(), 200, response);
        return new ServiceResult<>(response, 200, false);
    }

    // ================= 水下记录 =================

    @Transactional
    public ServiceResult<SegmentResponse> recordSegment(SegmentRequest request) {
        String fingerprint = fingerprint(KIND_SEGMENT, request.requestId(), request.operationNo(),
                request.diverId(), request.phase(), request.startDepthM(), request.endDepthM(),
                request.startedAt(), request.endedAt());
        IdempotentRow gate = beginIdempotency(KIND_SEGMENT, request.requestId(), fingerprint);
        if (gate != null) {
            return replay(gate, SegmentResponse.class);
        }
        OperationRow op = requireOperation(request.operationNo());
        if (OperationStatus.OBSERVING.name().equals(op.status())
                || OperationStatus.CLOSED.name().equals(op.status())
                || OperationStatus.CHAMBER.name().equals(op.status())) {
            throw BusinessException.unprocessable("SEGMENT_NOT_ALLOWED",
                    "潜次已出水/结案，不可再提交水下记录");
        }
        if (!op.diverA().equals(request.diverId()) && !op.diverB().equals(request.diverId())) {
            throw BusinessException.badRequest("DIVER_NOT_IN_GROUP",
                    "潜水员 " + request.diverId() + " 不属于该潜次两人组");
        }
        DivePhase phase = parsePhase(request.phase());
        validatePhaseGeometry(phase, request, op);
        if (request.endedAt() <= request.startedAt()) {
            throw BusinessException.badRequest("INVALID_TIME_SPAN", "结束时刻必须晚于开始时刻");
        }
        PlanRow plan = repository.findActivePlan(op.id())
                .orElseThrow(() -> BusinessException.notFound("PLAN_NOT_FOUND", "当前方案不存在"));
        long durationSeconds = request.endedAt() - request.startedAt();
        java.math.BigDecimal rate = phase == DivePhase.BOTTOM ? null
                : PlanCalculator.rateMetersPerMin(phase, request.startDepthM(), request.endDepthM(),
                        request.startedAt(), request.endedAt());
        boolean rateAlarm = rate != null && PlanCalculator.isRateAlarm(rate);

        String pairKey = op.id() + "|" + plan.version() + "|" + phase.name() + "|"
                + request.startDepthM() + "|" + request.endDepthM() + "|"
                + request.startedAt() + "|" + request.endedAt();
        // 串行化同一配对槽：并发两人提交在该行锁上排队，保证唯一成组。
        repository.lockPairKey(pairKey);

        long now = clock.instant().getEpochSecond();
        long segmentId = repository.insertSegment(new SegmentRow(null, op.id(), plan.version(),
                request.requestId(), request.diverId(), phase.name(),
                request.startDepthM(), request.endDepthM(), request.startedAt(), request.endedAt(),
                durationSeconds, rate, rateAlarm, "PENDING", pairKey, null, false, now));

        List<SegmentRow> pending = repository.findPendingSegments(op.id(), plan.version(), pairKey);
        SegmentRow partner = pending.stream()
                .filter(s -> !s.diverId().equals(request.diverId()))
                .findFirst().orElse(null);

        boolean effective = false;
        long ledgerSeconds = repository.sumLedgerOverlapSeconds(
                request.diverId(), request.startedAt() - DiveRules.ACCRUAL_WINDOW_SECONDS,
                request.startedAt());
        if (partner != null) {
            // 两条齐备：成对生效，台账入账并做 24h 累计上限校验（恰 6 小时合法）。
            long pairGroupId = repository.insertPairGroup(op.id(), pairKey, plan.version(), durationSeconds, now);
            repository.markSegmentEffective(partner.id(), pairGroupId);
            repository.markSegmentEffective(segmentId, pairGroupId);
            long partnerWindow = repository.sumLedgerOverlapSeconds(partner.diverId(),
                    request.startedAt() - DiveRules.ACCRUAL_WINDOW_SECONDS, request.startedAt());
            if (partnerWindow + durationSeconds > DiveRules.MAX_UNDERWATER_SECONDS_PER_24H) {
                throw BusinessException.unprocessable("ACCRUAL_LIMIT_EXCEEDED",
                        "潜水员 " + partner.diverId() + " 24 小时内累计水下时长将超过 6 小时");
            }
            if (ledgerSeconds + durationSeconds > DiveRules.MAX_UNDERWATER_SECONDS_PER_24H) {
                throw BusinessException.unprocessable("ACCRUAL_LIMIT_EXCEEDED",
                        "潜水员 " + request.diverId() + " 24 小时内累计水下时长将超过 6 小时");
            }
            repository.insertLedger(partner.diverId(), op.id(), pairGroupId,
                    partner.startedAt(), partner.endedAt(), durationSeconds, now);
            repository.insertLedger(request.diverId(), op.id(), pairGroupId,
                    request.startedAt(), request.endedAt(), durationSeconds, now);
            if (OperationStatus.PLANNED.name().equals(op.status())) {
                repository.updateStatusExpected(op.id(), OperationStatus.PLANNED.name(),
                        OperationStatus.EXECUTING.name());
            }
            if (rateAlarm) {
                String detail = phase.name() + " 速率 " + rate + " m/min 超过 18m/min";
                repository.insertEvent(op.id(), "RATE_ALARM",
                        request.diverId() + " " + detail, now);
                repository.insertEvent(op.id(), "RATE_ALARM",
                        partner.diverId() + " " + detail, now);
            }
            effective = true;
            ledgerSeconds = repository.sumLedgerOverlapSeconds(request.diverId(),
                    request.startedAt() - DiveRules.ACCRUAL_WINDOW_SECONDS, request.endedAt());
        }

        SegmentResponse response = new SegmentResponse(op.operationNo(), request.requestId(),
                segmentId, request.diverId(), phase.name(), plan.version(), effective,
                effective ? "EFFECTIVE" : "PENDING", rate, rateAlarm, ledgerSeconds);
        finishIdempotency(request.requestId(), 201, response);
        return new ServiceResult<>(response, 201, false);
    }

    // ================= 出水登记 =================

    @Transactional
    public ServiceResult<ExitResponse> exit(ExitRequest request) {
        String fingerprint = fingerprint(KIND_EXIT, request.requestId(),
                request.operationNo(), request.exitAt());
        IdempotentRow gate = beginIdempotency(KIND_EXIT, request.requestId(), fingerprint);
        if (gate != null) {
            return replay(gate, ExitResponse.class);
        }
        OperationRow op = requireOperation(request.operationNo());
        if (!OperationStatus.EXECUTING.name().equals(op.status())) {
            throw BusinessException.unprocessable("EXIT_NOT_ALLOWED",
                    "仅执行中潜次可登记出水，当前状态 " + op.status());
        }
        int pendingCount = repository.countPendingSegments(op.id());
        if (pendingCount > 0) {
            throw BusinessException.unprocessable("PENDING_SEGMENT_EXISTS",
                    "存在 " + pendingCount + " 条单人未配对记录，不得出水");
        }
        List<SegmentRow> segments = repository.findSegmentsByOperation(op.id());
        long effectiveCount = segments.stream().filter(s -> !s.frozen() && "EFFECTIVE".equals(s.pairState())).count();
        if (effectiveCount == 0) {
            throw BusinessException.unprocessable("NO_EFFECTIVE_SEGMENT",
                    "没有两人成对生效的水下记录，不得出水");
        }
        long lastEnded = segments.stream().filter(s -> !s.frozen())
                .mapToLong(SegmentRow::endedAt).max().orElse(Long.MIN_VALUE);
        if (request.exitAt() < lastEnded) {
            throw BusinessException.badRequest("EXIT_BEFORE_LAST_SEGMENT", "出水时刻早于最后水下记录结束时刻");
        }
        long dueAt = request.exitAt() + DiveRules.OBSERVATION_SECONDS;
        if (repository.markExit(op.id(), request.exitAt(), dueAt) == 0) {
            throw BusinessException.conflict("STATUS_CONFLICT", "潜次状态已变化，出水登记失败");
        }
        repository.insertEvent(op.id(), "EXITED",
                "出水，观察期满时刻 " + dueAt + "（恰满 2 小时方可结案）", request.exitAt());
        ExitResponse response = new ExitResponse(op.operationNo(), request.requestId(),
                OperationStatus.OBSERVING.name(), request.exitAt(), dueAt);
        finishIdempotency(request.requestId(), 200, response);
        return new ServiceResult<>(response, 200, false);
    }

    // ================= 观察期体征 =================

    @Transactional
    public ServiceResult<VitalsResponse> vitals(VitalsRequest request) {
        String fingerprint = fingerprint(KIND_VITALS, request.requestId(), request.operationNo(),
                request.diverId(), request.observedAt(), request.symptomatic(), request.symptoms());
        IdempotentRow gate = beginIdempotency(KIND_VITALS, request.requestId(), fingerprint);
        if (gate != null) {
            return replay(gate, VitalsResponse.class);
        }
        OperationRow op = requireOperation(request.operationNo());
        if (!op.diverA().equals(request.diverId()) && !op.diverB().equals(request.diverId())) {
            throw BusinessException.badRequest("DIVER_NOT_IN_GROUP",
                    "潜水员 " + request.diverId() + " 不属于该潜次两人组");
        }
        long now = clock.instant().getEpochSecond();

        if (OperationStatus.CLOSED.name().equals(op.status())) {
            // 结案终态：迟到体征只登记迟到事件，不改变任何状态（无论有无症状）。
            repository.insertVitals(op.id(), request.requestId(), request.diverId(),
                    request.observedAt(), request.symptomatic(), request.symptoms(), true, now);
            repository.insertEvent(op.id(), "VITALS_LATE",
                    "结案后迟到体征数据，symptomatic=" + request.symptomatic(), now);
            VitalsResponse response = new VitalsResponse(op.operationNo(), request.requestId(),
                    OperationStatus.CLOSED.name(), true, false);
            finishIdempotency(request.requestId(), 201, response);
            return new ServiceResult<>(response, 201, false);
        }
        if (!OperationStatus.OBSERVING.name().equals(op.status())
                && !OperationStatus.CHAMBER.name().equals(op.status())) {
            throw BusinessException.unprocessable("VITALS_NOT_ALLOWED",
                    "仅观察期可登记体征，当前状态 " + op.status());
        }
        if (op.exitAt() != null && request.observedAt() < op.exitAt()) {
            throw BusinessException.badRequest("VITALS_BEFORE_EXIT", "体征观测时刻早于出水时刻");
        }
        boolean chamberActivated = false;
        repository.insertVitals(op.id(), request.requestId(), request.diverId(),
                request.observedAt(), request.symptomatic(), request.symptoms(), false, now);
        String resultingStatus = op.status();
        if (request.symptomatic() && OperationStatus.OBSERVING.name().equals(op.status())) {
            if (repository.activateChamber(op.id(), now) > 0) {
                chamberActivated = true;
                resultingStatus = OperationStatus.CHAMBER.name();
                repository.insertEvent(op.id(), "CHAMBER_ACTIVATED",
                        "观察期出现症状（" + Optional.ofNullable(request.symptoms()).orElse("未描述")
                                + "），启动加压舱预案并冻结结案", now);
            }
        }
        VitalsResponse response = new VitalsResponse(op.operationNo(), request.requestId(),
                resultingStatus, false, chamberActivated);
        finishIdempotency(request.requestId(), 201, response);
        return new ServiceResult<>(response, 201, false);
    }

    // ================= 结案 =================

    @Transactional
    public ServiceResult<CloseResponse> close(CloseRequest request) {
        OperationRow op = requireOperation(request.operationNo());
        // 结案为终态：任意 requestId 的重复结案都返回幂等成功。
        if (OperationStatus.CLOSED.name().equals(op.status())) {
            return new ServiceResult<>(new CloseResponse(op.operationNo(), request.requestId(),
                    OperationStatus.CLOSED.name(), op.closedAt(), true), 200, true);
        }
        String fingerprint = fingerprint(KIND_CLOSE, request.requestId(),
                request.operationNo(), request.closedAt());
        IdempotentRow gate = beginIdempotency(KIND_CLOSE, request.requestId(), fingerprint);
        if (gate != null) {
            return replay(gate, CloseResponse.class);
        }
        if (OperationStatus.CHAMBER.name().equals(op.status())) {
            throw BusinessException.unprocessable("CLOSE_FROZEN",
                    "观察期出现症状已启动加压舱预案，结案冻结");
        }
        if (!OperationStatus.OBSERVING.name().equals(op.status())) {
            throw BusinessException.unprocessable("CLOSE_NOT_ALLOWED",
                    "仅观察期满且无症状的潜次可结案，当前状态 " + op.status());
        }
        if (request.closedAt() < op.observationDueAt()) {
            throw BusinessException.unprocessable("OBSERVATION_NOT_COMPLETE",
                    "须出水后观察满 2 小时方可结案，还差 "
                            + (op.observationDueAt() - request.closedAt()) + " 秒");
        }
        if (repository.markClosed(op.id(), request.closedAt()) == 0) {
            throw BusinessException.conflict("STATUS_CONFLICT", "潜次状态已变化，结案失败");
        }
        repository.releaseGroup(op.groupKey());
        repository.insertEvent(op.id(), "CLOSED", "观察满 2 小时无症状，结案（终态）", request.closedAt());
        CloseResponse response = new CloseResponse(op.operationNo(), request.requestId(),
                OperationStatus.CLOSED.name(), request.closedAt(), false);
        finishIdempotency(request.requestId(), 200, response);
        return new ServiceResult<>(response, 200, false);
    }

    // ================= 内部辅助 =================

    /** 只读复算详情：当前方案剖面、全部水下记录、台账与事件。 */
    @Transactional(readOnly = true)
    public OperationDetailResponse getOperationDetail(String operationNo) {
        OperationRow op = requireOperation(operationNo);
        PlanRow plan = repository.findActivePlan(op.id())
                .orElseThrow(() -> BusinessException.notFound("PLAN_NOT_FOUND", "当前方案不存在"));
        List<PlanProfileStep> profile = readProfile(plan.profileJson());
        List<OperationDetailResponse.SegmentView> segmentViews =
                repository.findSegmentsByOperation(op.id()).stream()
                        .map(s -> new OperationDetailResponse.SegmentView(
                                s.diverId(), s.phase(), s.startDepthM(), s.endDepthM(),
                                s.startedAt(), s.endedAt(), s.durationSeconds(),
                                s.rateMPerMin(), s.rateAlarm(), s.pairState(),
                                s.planVersion(), s.frozen()))
                        .toList();
        List<OperationDetailResponse.LedgerView> ledgerViews =
                repository.findLedgerByOperation(op.id()).stream()
                        .map(l -> new OperationDetailResponse.LedgerView(
                                l.diverId(), l.startedAt(), l.endedAt(), l.durationSeconds()))
                        .toList();
        List<OperationDetailResponse.EventView> eventViews =
                repository.findEventEntries(op.id()).stream()
                        .map(e -> new OperationDetailResponse.EventView(
                                e.eventType(), e.detail(), e.createdAt()))
                        .toList();
        return new OperationDetailResponse(op.operationNo(), op.status(), op.depthM(),
                op.bottomMinutes(), op.planVersion(), op.exitAt(), op.observationDueAt(),
                op.chamberAt(), op.closedAt(), profile, segmentViews, ledgerViews, eventViews);
    }

    private List<PlanProfileStep> readProfile(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PlanProfileStep.class));
        } catch (Exception ex) {
            throw new IllegalStateException("无法解析方案剖面快照", ex);
        }
    }

    private OperationRow requireOperation(String operationNo) {
        return repository.findOperationByNo(operationNo)
                .orElseThrow(() -> BusinessException.notFound("OPERATION_NOT_FOUND",
                        "潜次 " + operationNo + " 不存在"));
    }

    private int[] lookupDeco(int depthM) {
        return repository.findDecoEntry(depthM)
                .orElseThrow(() -> BusinessException.unprocessable("DEPTH_NOT_IN_TABLE",
                        "合成减压表无 " + depthM + " 米档位"));
    }

    private DivePhase parsePhase(String phase) {
        try {
            return DivePhase.valueOf(phase);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("INVALID_PHASE", "未知记录相位 " + phase);
        }
    }

    private void validatePhaseGeometry(DivePhase phase, SegmentRequest request, OperationRow op) {
        int start = request.startDepthM();
        int end = request.endDepthM();
        if (start < 0 || end < 0) {
            throw BusinessException.badRequest("INVALID_DEPTH", "深度不可为负");
        }
        int planDepth = op.depthM();
        if (start > planDepth || end > planDepth) {
            throw BusinessException.badRequest("DEPTH_EXCEEDS_PLAN",
                    "记录深度超过方案深度 " + planDepth + " 米");
        }
        switch (phase) {
            case DESCEND -> {
                if (end <= start) {
                    throw BusinessException.badRequest("INVALID_GEOMETRY", "下潜段深度必须递增");
                }
            }
            case ASCEND -> {
                if (end >= start) {
                    throw BusinessException.badRequest("INVALID_GEOMETRY", "上升段深度必须递减");
                }
            }
            case BOTTOM -> {
                if (end != start) {
                    throw BusinessException.badRequest("INVALID_GEOMETRY", "水底段起止深度必须相同");
                }
            }
        }
    }

    private static String groupKey(String diverA, String diverB) {
        String lo = diverA.compareTo(diverB) <= 0 ? diverA : diverB;
        String hi = lo.equals(diverA) ? diverB : diverA;
        return lo + "|" + hi;
    }

    private String newOperationNo(long nowEpochSeconds) {
        byte[] random = new byte[3];
        ThreadLocalRandom.current().nextBytes(random);
        return "DV-" + Long.toUnsignedString(nowEpochSeconds, 36)
                + "-" + HexFormat.of().formatHex(random);
    }

    private String fingerprint(String kind, Object... fields) {
        StringBuilder sb = new StringBuilder(kind);
        for (Object field : fields) {
            sb.append('|').append(field == null ? "\0" : field.toString());
        }
        return Fingerprints.sha256(sb.toString());
    }

    /**
     * 开启幂等门。
     *
     * @return 已存在台账行（同键同参，调用方重放）；null 表示本次为首次请求，继续执行业务
     */
    private IdempotentRow beginIdempotency(String kind, String requestId, String fingerprint) {
        Optional<IdempotentRow> existing = repository.findIdempotent(requestId);
        if (existing.isPresent()) {
            IdempotentRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict("IDEMPOTENT_FINGERPRINT_MISMATCH",
                        "requestId=" + requestId + " 已用于不同的业务参数");
            }
            return row;
        }
        long now = clock.instant().getEpochSecond();
        if (repository.tryInsertIdempotent(new IdempotentRow(
                requestId, kind, fingerprint, 0, "", now))) {
            return null;
        }
        // 并发同 requestId：等待对方事务落库后按重放/冲突处理。
        for (int i = 0; i < 60; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
            Optional<IdempotentRow> raced = repository.findIdempotent(requestId);
            if (raced.isPresent() && raced.get().httpStatus() != 0) {
                IdempotentRow row = raced.get();
                if (!row.fingerprint().equals(fingerprint)) {
                    throw BusinessException.conflict("IDEMPOTENT_FINGERPRINT_MISMATCH",
                            "requestId=" + requestId + " 已用于不同的业务参数");
                }
                return row;
            }
        }
        throw BusinessException.conflict("IDEMPOTENT_RACE", "并发的同一 requestId 请求尚未完成");
    }

    private void finishIdempotency(String requestId, int httpStatus, Object response) {
        repository.updateIdempotentResponse(requestId, httpStatus, writeJson(response));
    }

    private <T> ServiceResult<T> replay(IdempotentRow row, Class<T> type) {
        T body;
        try {
            body = objectMapper.readValue(row.responseJson().getBytes(StandardCharsets.UTF_8), type);
        } catch (Exception ex) {
            throw new IllegalStateException("无法解析幂等响应快照", ex);
        }
        return new ServiceResult<>(body, row.httpStatus(), true);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }
}
