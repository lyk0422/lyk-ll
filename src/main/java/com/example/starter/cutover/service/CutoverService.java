package com.example.starter.cutover.service;

import com.example.starter.cutover.api.AnalysisResponse;
import com.example.starter.cutover.api.ApplicationResponse;
import com.example.starter.cutover.api.ApplyRequest;
import com.example.starter.cutover.api.ReportRequest;
import com.example.starter.cutover.api.ReportResponse;
import com.example.starter.cutover.api.RollbackRecordView;
import com.example.starter.cutover.api.RollbackResponse;
import com.example.starter.cutover.api.SealResponse;
import com.example.starter.cutover.domain.ApplicationStatus;
import com.example.starter.cutover.domain.Priority;
import com.example.starter.cutover.domain.ReportPhase;
import com.example.starter.cutover.domain.ReportResult;
import com.example.starter.cutover.domain.RollbackReason;
import com.example.starter.cutover.domain.StepStatus;
import com.example.starter.cutover.domain.StepType;
import com.example.starter.cutover.po.ApplicationPO;
import com.example.starter.cutover.po.RollbackRecordPO;
import com.example.starter.cutover.po.StepPO;
import com.example.starter.cutover.po.StepReportEventPO;
import com.example.starter.cutover.repository.ApplicationRepository;
import com.example.starter.cutover.repository.IdempotencyRepository;
import com.example.starter.cutover.repository.MasterDataRepository;
import com.example.starter.cutover.repository.RollbackRecordRepository;
import com.example.starter.cutover.repository.StepReportEventRepository;
import com.example.starter.cutover.repository.StepRepository;
import com.example.starter.cutover.support.BusinessValidationException;
import com.example.starter.cutover.support.ConflictException;
import com.example.starter.cutover.support.FingerprintUtil;
import com.example.starter.cutover.support.NotFoundException;
import com.example.starter.cutover.support.TimeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据中心割接核心服务：申请、影响分析、审批、窗口执行、步骤回报、
 * 回退判据（验证点超时/硬性检查失败/步骤失败/窗口超期）与封板。
 *
 * <p>并发控制：写事务先对申请行 SELECT ... FOR UPDATE，串行化同一申请的状态变更；
 * 审批前对设备行加锁以保证“同设备同一时间只允许一个已批准割接”；
 * rollback_record.cutover_id 唯一约束 + 申请状态 CAS 共同保证回退/封板唯一终态。
 */
@Service
public class CutoverService {

    /** 割接窗口最长时长（小时）；恰 4 小时合法。 */
    public static final long MAX_WINDOW_HOURS = 4;
    /** 关键验证点超时时长（分钟）；恰满 10 分钟判定超时。 */
    public static final long VERIFY_TIMEOUT_MINUTES = 10;

    private static final String SCOPE_APPLY = "APPLY";
    private static final String SCOPE_STEP_REPORT = "STEP_REPORT";

    private final ApplicationRepository applicationRepository;
    private final StepRepository stepRepository;
    private final RollbackRecordRepository rollbackRecordRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final StepReportEventRepository stepReportEventRepository;
    private final MasterDataRepository masterDataRepository;
    private final TimeSource timeSource;
    private final ObjectMapper objectMapper;

    public CutoverService(ApplicationRepository applicationRepository,
                          StepRepository stepRepository,
                          RollbackRecordRepository rollbackRecordRepository,
                          IdempotencyRepository idempotencyRepository,
                          StepReportEventRepository stepReportEventRepository,
                          MasterDataRepository masterDataRepository,
                          TimeSource timeSource,
                          ObjectMapper objectMapper) {
        this.applicationRepository = applicationRepository;
        this.stepRepository = stepRepository;
        this.rollbackRecordRepository = rollbackRecordRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.stepReportEventRepository = stepReportEventRepository;
        this.masterDataRepository = masterDataRepository;
        this.timeSource = timeSource;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交割接申请（幂等：同键同参返回首次结果，同键异参 409）。
     */
    @Transactional
    public ApplicationResponse apply(ApplyRequest request) {
        validateApplyRequest(request);
        String fingerprint = FingerprintUtil.fingerprint(request);

        Optional<com.example.starter.cutover.po.IdempotencyRecordPO> existing =
                idempotencyRepository.find(request.requestId(), SCOPE_APPLY);
        if (existing.isPresent()) {
            return resolveApplyReplay(existing.get(), fingerprint);
        }

        LocalDateTime now = timeSource.now();
        ApplicationPO po = new ApplicationPO();
        po.setRequestId(request.requestId());
        po.setDeviceCode(request.deviceCode());
        po.setTitle(request.title());
        po.setPriority(Priority.valueOf(request.priority()).name());
        po.setWindowStart(request.windowStart());
        po.setWindowEnd(request.windowEnd());
        po.setStatus(ApplicationStatus.CREATED.name());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);

        boolean inserted;
        try {
            inserted = idempotencyRepository.tryInsert(request.requestId(), SCOPE_APPLY,
                    fingerprint, null, null, now);
        } catch (DuplicateKeyException e) {
            inserted = false;
        }
        if (!inserted) {
            return resolveApplyReplay(
                    idempotencyRepository.find(request.requestId(), SCOPE_APPLY).orElseThrow(),
                    fingerprint);
        }

        applicationRepository.insert(po);
        int seq = 1;
        for (ApplyRequest.StepSpec spec : request.steps()) {
            StepPO step = new StepPO();
            step.setCutoverId(po.getId());
            step.setSeq(seq++);
            step.setName(spec.name());
            step.setStepType(StepType.valueOf(spec.stepType()).name());
            step.setStatus(StepStatus.PENDING.name());
            stepRepository.insert(step);
        }

        ApplicationResponse response = loadApplicationResponse(po.getId(), false);
        idempotencyRepository.updateOutcome(
                idempotencyRepository.find(request.requestId(), SCOPE_APPLY).orElseThrow().getId(),
                String.valueOf(po.getId()), writeJson(response));
        return response;
    }

    /**
     * 影响分析：列出全部关联业务与同窗口重叠变更，CREATED -&gt; ANALYZED。
     */
    @Transactional
    public AnalysisResponse analyze(Long applicationId) {
        ApplicationPO app = lockApplication(applicationId);
        if (app.getStatus().equals(ApplicationStatus.CREATED.name())) {
            applicationRepository.updateStatus(app.getId(), ApplicationStatus.ANALYZED, timeSource.now());
            app.setStatus(ApplicationStatus.ANALYZED.name());
        }
        return buildAnalysis(app);
    }

    /**
     * 只读复算影响分析清单。
     */
    @Transactional(readOnly = true)
    public AnalysisResponse viewAnalysis(Long applicationId) {
        ApplicationPO app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("割接申请不存在: " + applicationId));
        return buildAnalysis(app);
    }

    /**
     * 审批：半开窗口冲突或高优先级抢占均拒绝。
     */
    @Transactional
    public ApplicationResponse approve(Long applicationId) {
        ApplicationPO app = lockApplication(applicationId);
        ApplicationStatus status = ApplicationStatus.valueOf(app.getStatus());
        if (status != ApplicationStatus.ANALYZED) {
            if (status == ApplicationStatus.APPROVED || status == ApplicationStatus.EXECUTING
                    || status == ApplicationStatus.VERIFIED || status == ApplicationStatus.SEALED) {
                return loadApplicationResponse(app.getId(), false);
            }
            if (status == ApplicationStatus.REJECTED) {
                throw new ConflictException("ALREADY_REJECTED", "该申请已被拒绝，不能再次审批");
            }
            throw new BusinessValidationException("当前状态 " + status + " 不允许审批");
        }

        // 设备行锁串行化同设备审批，窗口占用判定落在锁内，杜绝并发批准重叠窗口
        masterDataRepository.lockDevice(app.getDeviceCode());
        List<ApplicationPO> overlaps = applicationRepository.findWindowHoldingOverlaps(
                app.getDeviceCode(), app.getWindowStart(), app.getWindowEnd(), app.getId());
        if (!overlaps.isEmpty()) {
            Priority self = Priority.valueOf(app.getPriority());
            boolean higher = overlaps.stream()
                    .anyMatch(o -> Priority.valueOf(o.getPriority()).getRank() > self.getRank());
            String reason = higher
                    ? "同窗口存在高优先级变更，低优先级申请拒绝"
                    : "同一设备同一时间只允许一个已批准割接，窗口半开区间重叠";
            applicationRepository.compareAndUpdateStatus(app.getId(),
                    List.of(ApplicationStatus.ANALYZED), ApplicationStatus.REJECTED,
                    reason, timeSource.now());
            return loadApplicationResponse(app.getId(), false);
        }

        applicationRepository.compareAndUpdateStatus(app.getId(),
                List.of(ApplicationStatus.ANALYZED), ApplicationStatus.APPROVED,
                null, timeSource.now());
        return loadApplicationResponse(app.getId(), false);
    }

    /**
     * 进入窗口执行：APPROVED -&gt; EXECUTING。
     */
    @Transactional
    public ApplicationResponse execute(Long applicationId) {
        ApplicationPO app = lockApplication(applicationId);
        ApplicationStatus status = ApplicationStatus.valueOf(app.getStatus());
        if (status == ApplicationStatus.APPROVED) {
            applicationRepository.compareAndUpdateStatus(app.getId(),
                    List.of(ApplicationStatus.APPROVED), ApplicationStatus.EXECUTING,
                    null, timeSource.now());
        } else if (status != ApplicationStatus.EXECUTING) {
            throw new BusinessValidationException("当前状态 " + status + " 不允许进入窗口执行");
        }
        return loadApplicationResponse(app.getId(), false);
    }

    /**
     * 步骤逐条回报（START/RESULT），按 requestId 幂等；失败/超时/超期同事务回退。
     */
    @Transactional
    public ReportResponse report(Long applicationId, Integer stepSeq, ReportRequest request) {
        if (stepSeq == null || stepSeq < 1) {
            throw new BusinessValidationException("stepSeq 必须为正整数");
        }
        ReportPhase phase;
        ReportResult result;
        try {
            phase = ReportPhase.valueOf(request.phase());
        } catch (IllegalArgumentException e) {
            throw new BusinessValidationException("phase 必须为 START 或 RESULT");
        }
        if (phase == ReportPhase.RESULT) {
            if (request.result() == null || request.result().isBlank()) {
                throw new BusinessValidationException("RESULT 回报必须携带 result");
            }
            try {
                result = ReportResult.valueOf(request.result());
            } catch (IllegalArgumentException e) {
                throw new BusinessValidationException("result 必须为 SUCCESS/FAIL/HARD_FAIL");
            }
        } else {
            result = null;
        }

        // 指纹覆盖 requestId 与全部业务字段（含路径上的申请、步骤标识与回报内容）
        String fingerprint = FingerprintUtil.fingerprint(new StepReportFingerprint(
                applicationId, stepSeq, request.requestId(), phase.name(),
                result == null ? null : result.name()));

        // 申请行锁串行化同一割接的全部回报/判据/封板；锁内先查幂等再校验状态机
        ApplicationPO app = lockApplication(applicationId);
        ApplicationStatus appStatus = ApplicationStatus.valueOf(app.getStatus());

        Optional<com.example.starter.cutover.po.IdempotencyRecordPO> existing =
                idempotencyRepository.find(request.requestId(), SCOPE_STEP_REPORT);
        if (existing.isPresent()) {
            return resolveReportReplay(existing.get(), fingerprint);
        }

        // 终态后补报：只记迟到事件，不改变任何状态（锁内登记幂等并留痕）
        if (appStatus == ApplicationStatus.SEALED || appStatus == ApplicationStatus.ROLLED_BACK
                || appStatus == ApplicationStatus.WINDOW_OVERDUE) {
            return handleLateReport(app, stepSeq, request.requestId(), result, fingerprint, appStatus);
        }
        if (appStatus == ApplicationStatus.REJECTED) {
            throw new ConflictException("APPLICATION_REJECTED", "申请已拒绝，不能回报步骤");
        }
        if (appStatus != ApplicationStatus.EXECUTING) {
            throw new BusinessValidationException("当前状态 " + appStatus + " 不允许回报步骤，请先进入窗口执行");
        }

        List<StepPO> steps = stepRepository.findByCutoverId(applicationId);
        StepPO step = steps.stream().filter(s -> s.getSeq().equals(stepSeq)).findFirst()
                .orElseThrow(() -> new NotFoundException("步骤不存在: " + stepSeq));

        // 非法首次请求在此被拒，不占用 requestId；通过校验后再登记幂等
        validateTransition(phase, step, steps);

        boolean registered;
        try {
            registered = idempotencyRepository.tryInsert(request.requestId(),
                    SCOPE_STEP_REPORT, fingerprint, String.valueOf(step.getId()), null,
                    timeSource.now());
        } catch (DuplicateKeyException e) {
            registered = false;
        }
        if (!registered) {
            // 并发同键请求已抢先登记（行锁串行下仅理论可能），返回首次结果或 409
            return resolveReportReplay(
                    idempotencyRepository.find(request.requestId(), SCOPE_STEP_REPORT).orElseThrow(),
                    fingerprint);
        }

        LocalDateTime now = timeSource.now();
        if (phase == ReportPhase.START) {
            boolean cas = stepRepository.compareAndUpdateStatus(step.getId(),
                    List.of(StepStatus.PENDING), StepStatus.RUNNING, now, null);
            if (!cas) {
                throw new ConflictException("STEP_STATE_CHANGED", "步骤状态已变化，请以首次结果为准");
            }
            step.setStatus(StepStatus.RUNNING.name());
            step.setStartedAt(now);
            ReportResponse response = new ReportResponse(applicationId, stepSeq,
                    StepStatus.RUNNING.name(), ApplicationStatus.EXECUTING.name(),
                    false, null, false, false);
            storeReportOutcome(request.requestId(), step.getId(), response);
            return response;
        }

        // RESULT 阶段：先判窗口终点口径（恰在终点完成有效，越过终点的回报判超期）
        if (now.isAfter(app.getWindowEnd())) {
            RollbackResponse rb = doRollback(app, steps, RollbackReason.WINDOW_OVERDUE,
                    "回报时间 " + now + " 晚于窗口终点 " + app.getWindowEnd(),
                    step.getId(), now);
            ReportResponse response = new ReportResponse(applicationId, stepSeq,
                    StepStatus.FROZEN.name(), rb.applicationStatus(),
                    true, RollbackReason.WINDOW_OVERDUE.name(), false, false);
            storeReportOutcome(request.requestId(), step.getId(), response);
            return response;
        }

        if (result != ReportResult.SUCCESS) {
            // 任一硬性检查失败/普通失败：本步骤置 FAILED，同事务冻结后续并回退
            stepRepository.compareAndUpdateStatus(step.getId(),
                    List.of(StepStatus.RUNNING), StepStatus.FAILED, null, now);
            RollbackReason reason = result == ReportResult.HARD_FAIL
                    ? RollbackReason.HARD_CHECK_FAILED
                    : RollbackReason.STEP_FAILED;
            RollbackResponse rb = doRollback(app, steps, reason,
                    (reason == RollbackReason.HARD_CHECK_FAILED ? "关键验证点硬性检查失败：" : "步骤执行失败：")
                            + step.getName(),
                    step.getId(), now);
            ReportResponse response = new ReportResponse(applicationId, stepSeq,
                    StepStatus.FAILED.name(), rb.applicationStatus(),
                    true, reason.name(), false, false);
            storeReportOutcome(request.requestId(), step.getId(), response);
            return response;
        }

        // VERIFY 验证点恰满 10 分钟未通过即判超时（>= 判定）
        if (StepType.VERIFY.name().equals(step.getStepType())
                && !Duration.between(step.getStartedAt(), now).minusMinutes(VERIFY_TIMEOUT_MINUTES).isNegative()) {
            RollbackResponse rb = doRollback(app, steps, RollbackReason.VERIFY_TIMEOUT,
                    "关键验证点 " + step.getName() + " 自 " + step.getStartedAt()
                            + " 起恰满/超过 10 分钟未通过",
                    step.getId(), now);
            ReportResponse response = new ReportResponse(applicationId, stepSeq,
                    StepStatus.FROZEN.name(), rb.applicationStatus(),
                    true, RollbackReason.VERIFY_TIMEOUT.name(), false, false);
            storeReportOutcome(request.requestId(), step.getId(), response);
            return response;
        }

        boolean cas = stepRepository.compareAndUpdateStatus(step.getId(),
                List.of(StepStatus.RUNNING), StepStatus.DONE, null, now);
        if (!cas) {
            throw new ConflictException("STEP_STATE_CHANGED", "步骤状态已变化，请以首次结果为准");
        }
        step.setStatus(StepStatus.DONE.name());
        step.setCompletedAt(now);
        steps = stepRepository.findByCutoverId(applicationId);

        boolean allDone = steps.stream().allMatch(s -> StepStatus.DONE.name().equals(s.getStatus()));
        ApplicationStatus newStatus = allDone ? ApplicationStatus.VERIFIED : ApplicationStatus.EXECUTING;
        if (allDone) {
            applicationRepository.compareAndUpdateStatus(app.getId(),
                    List.of(ApplicationStatus.EXECUTING), ApplicationStatus.VERIFIED, null, now);
        }
        ReportResponse response = new ReportResponse(applicationId, stepSeq,
                StepStatus.DONE.name(), newStatus.name(), false, null, false, false);
        storeReportOutcome(request.requestId(), step.getId(), response);
        return response;
    }

    /**
     * 判据巡检：复算验证点超时与窗口超期，命中则自动回退。
     */
    @Transactional
    public RollbackResponse evaluate(Long applicationId) {
        ApplicationPO app = lockApplication(applicationId);
        ApplicationStatus status = ApplicationStatus.valueOf(app.getStatus());
        if (status.isTerminal()) {
            return terminalRollbackView(app);
        }
        if (status == ApplicationStatus.CREATED || status == ApplicationStatus.ANALYZED
                || status == ApplicationStatus.REJECTED) {
            throw new BusinessValidationException("当前状态 " + status + " 不进行判据巡检");
        }
        LocalDateTime now = timeSource.now();
        List<StepPO> steps = stepRepository.findByCutoverId(applicationId);

        // 判据一：关键验证点恰满 10 分钟仍 RUNNING 即超时
        for (StepPO step : steps) {
            if (StepType.VERIFY.name().equals(step.getStepType())
                    && StepStatus.RUNNING.name().equals(step.getStatus())
                    && step.getStartedAt() != null
                    && !Duration.between(step.getStartedAt(), now).minusMinutes(VERIFY_TIMEOUT_MINUTES).isNegative()) {
                return doRollback(app, steps, RollbackReason.VERIFY_TIMEOUT,
                        "关键验证点 " + step.getName() + " 超时：自 " + step.getStartedAt()
                                + " 起恰满/超过 10 分钟未通过",
                        step.getId(), now);
            }
        }

        // 判据二：窗口结束（恰终点判定）仍有未完成步骤 -> 窗口超期强制回退
        boolean unfinished = steps.stream()
                .anyMatch(s -> StepStatus.PENDING.name().equals(s.getStatus())
                        || StepStatus.RUNNING.name().equals(s.getStatus()));
        if (!now.isBefore(app.getWindowEnd()) && unfinished) {
            return doRollback(app, steps, RollbackReason.WINDOW_OVERDUE,
                    "窗口终点 " + app.getWindowEnd() + " 仍有未完成步骤，强制回退",
                    null, now);
        }

        return new RollbackResponse(applicationId, status.name(), null,
                "未命中任何回退判据", 0, false);
    }

    /**
     * 人工强制回退。
     */
    @Transactional
    public RollbackResponse forceRollback(Long applicationId, String reason) {
        ApplicationPO app = lockApplication(applicationId);
        ApplicationStatus status = ApplicationStatus.valueOf(app.getStatus());
        if (status == ApplicationStatus.ROLLED_BACK || status == ApplicationStatus.WINDOW_OVERDUE) {
            return terminalRollbackView(app);
        }
        if (status == ApplicationStatus.SEALED) {
            throw new ConflictException("ALREADY_SEALED", "已封板，不能回退");
        }
        if (status == ApplicationStatus.VERIFIED) {
            throw new ConflictException("ALREADY_VERIFIED", "已验证通过，请封板而非回退");
        }
        if (status != ApplicationStatus.EXECUTING && status != ApplicationStatus.APPROVED) {
            throw new BusinessValidationException("当前状态 " + status + " 不允许回退");
        }
        List<StepPO> steps = stepRepository.findByCutoverId(applicationId);
        String detail = reason == null || reason.isBlank() ? "人工强制回退" : reason;
        return doRollback(app, steps, RollbackReason.FORCED, detail, null, timeSource.now());
    }

    /**
     * 封板（终态）；重复封板幂等成功；已回退则 409。
     */
    @Transactional
    public SealResponse seal(Long applicationId) {
        ApplicationPO app = lockApplication(applicationId);
        ApplicationStatus status = ApplicationStatus.valueOf(app.getStatus());
        if (status == ApplicationStatus.SEALED) {
            return new SealResponse(applicationId, ApplicationStatus.SEALED.name(), true);
        }
        if (status == ApplicationStatus.ROLLED_BACK || status == ApplicationStatus.WINDOW_OVERDUE
                || status == ApplicationStatus.REJECTED) {
            throw new ConflictException("TERMINAL_CONFLICT",
                    "申请终态为 " + status + "，不得封板，唯一终态不可更改");
        }
        if (status != ApplicationStatus.VERIFIED) {
            throw new BusinessValidationException("当前状态 " + status + "，全部关键验证通过后才能封板");
        }
        boolean cas = applicationRepository.compareAndUpdateStatus(app.getId(),
                List.of(ApplicationStatus.VERIFIED), ApplicationStatus.SEALED, null, timeSource.now());
        if (!cas) {
            ApplicationPO latest = applicationRepository.findById(applicationId).orElseThrow();
            ApplicationStatus latestStatus = ApplicationStatus.valueOf(latest.getStatus());
            if (latestStatus == ApplicationStatus.SEALED) {
                return new SealResponse(applicationId, ApplicationStatus.SEALED.name(), true);
            }
            throw new ConflictException("TERMINAL_CONFLICT", "并发终态竞争落败: " + latestStatus);
        }
        return new SealResponse(applicationId, ApplicationStatus.SEALED.name(), false);
    }

    /**
     * 查询申请详情（含步骤时序）。
     */
    @Transactional(readOnly = true)
    public ApplicationResponse getApplication(Long applicationId) {
        ApplicationPO po = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("割接申请不存在: " + applicationId));
        return toResponse(po, stepRepository.findByCutoverId(applicationId), false);
    }

    /**
     * 只读复算回退判据命中记录（含回退时点步骤快照）。
     */
    @Transactional(readOnly = true)
    public RollbackRecordView getRollbackRecord(Long applicationId) {
        applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("割接申请不存在: " + applicationId));
        Optional<RollbackRecordPO> record = rollbackRecordRepository.findByCutoverId(applicationId);
        if (record.isEmpty()) {
            return new RollbackRecordView(false, null, null, null, null, null);
        }
        RollbackRecordPO r = record.get();
        Integer stepSeq = null;
        if (r.getStepId() != null) {
            stepSeq = stepRepository.findByCutoverId(applicationId).stream()
                    .filter(s -> s.getId().equals(r.getStepId()))
                    .map(StepPO::getSeq)
                    .findFirst().orElse(null);
        }
        return new RollbackRecordView(true, r.getTriggerReason(), r.getDetail(),
                stepSeq, r.getSnapshotJson(), r.getRolledBackAt());
    }

    // ---------------------------------------------------------------------
    // 内部方法（置于类作用域末尾）
    // ---------------------------------------------------------------------

    /**
     * 在同一事务内下达回退指令：写唯一回退记录、冻结全部未完成步骤、CAS 终态。
     * 任一步失败整体回滚，不会出现部分步骤继续执行。
     */
    private RollbackResponse doRollback(ApplicationPO app, List<StepPO> steps,
                                        RollbackReason reason, String detail,
                                        Long triggerStepId, LocalDateTime now) {
        ApplicationStatus targetStatus = reason == RollbackReason.WINDOW_OVERDUE
                ? ApplicationStatus.WINDOW_OVERDUE
                : ApplicationStatus.ROLLED_BACK;

        RollbackRecordPO record = new RollbackRecordPO();
        record.setCutoverId(app.getId());
        record.setTriggerReason(reason.name());
        record.setDetail(detail);
        record.setStepId(triggerStepId);
        record.setSnapshotJson(writeJson(buildStepSnapshot(steps)));
        record.setRolledBackAt(now);
        try {
            rollbackRecordRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发回退落败：唯一终态归先到者，本事务不再冻结任何步骤
            RollbackRecordPO winner = rollbackRecordRepository.findByCutoverId(app.getId()).orElseThrow();
            String latestStatus = applicationRepository.findById(app.getId()).orElseThrow().getStatus();
            return new RollbackResponse(app.getId(), latestStatus,
                    winner.getTriggerReason(), winner.getDetail(), 0, true);
        }

        int frozen = stepRepository.freezeUnfinished(app.getId());
        boolean cas = applicationRepository.compareAndUpdateStatus(app.getId(),
                List.of(ApplicationStatus.APPROVED, ApplicationStatus.EXECUTING),
                targetStatus, null, now);
        if (!cas) {
            // 行锁内理论不可达；唯一约束兜底，抛出后整体回滚
            throw new ConflictException("TERMINAL_CONFLICT", "终态竞争失败，回退指令撤销");
        }
        return new RollbackResponse(app.getId(), targetStatus.name(), reason.name(),
                detail, frozen, false);
    }

    /**
     * 终态后补报步骤：登记迟到事件（幂等），不改状态。
     */
    private ReportResponse handleLateReport(ApplicationPO app, int stepSeq, String requestId,
                                            ReportResult result, String fingerprint,
                                            ApplicationStatus appStatus) {
        LocalDateTime now = timeSource.now();
        ReportResponse response = new ReportResponse(app.getId(), stepSeq,
                StepStatus.FROZEN.name(), appStatus.name(), false, null, true, false);

        boolean inserted;
        try {
            inserted = idempotencyRepository.tryInsert(requestId, SCOPE_STEP_REPORT,
                    fingerprint, null, writeJson(response), now);
        } catch (DuplicateKeyException e) {
            inserted = false;
        }
        if (!inserted) {
            return resolveReportReplay(
                    idempotencyRepository.find(requestId, SCOPE_STEP_REPORT).orElseThrow(),
                    fingerprint);
        }

        // 幂等登记成功后写迟到事件（仅留痕，不改变步骤与申请状态）
        StepReportEventPO event = new StepReportEventPO();
        event.setCutoverId(app.getId());
        event.setStepSeq(stepSeq);
        event.setRequestId(requestId);
        event.setResult(result == null ? "START" : result.name());
        event.setNote("终态 " + appStatus + " 后补报，仅记迟到事件");
        event.setReportedAt(now);
        stepReportEventRepository.insert(event);
        return response;
    }

    private void validateTransition(ReportPhase phase, StepPO step, List<StepPO> steps) {
        StepStatus state = StepStatus.valueOf(step.getStatus());
        if (phase == ReportPhase.START) {
            if (state == StepStatus.DONE) {
                throw new ConflictException("STEP_ALREADY_DONE", "步骤 " + step.getSeq() + " 已完成");
            }
            if (state == StepStatus.RUNNING) {
                throw new ConflictException("STEP_ALREADY_RUNNING", "步骤 " + step.getSeq() + " 已在执行中");
            }
            if (state == StepStatus.FROZEN) {
                throw new ConflictException("STEP_FROZEN", "步骤 " + step.getSeq() + " 已冻结");
            }
            if (step.getSeq() > 1) {
                StepPO prev = steps.stream().filter(s -> s.getSeq() == step.getSeq() - 1).findFirst()
                        .orElseThrow(() -> new BusinessValidationException("缺失前序步骤 " + (step.getSeq() - 1)));
                if (!StepStatus.DONE.name().equals(prev.getStatus())) {
                    throw new BusinessValidationException(
                            "步骤须按序执行，前序步骤 " + prev.getSeq() + " 尚未完成");
                }
            }
        } else {
            if (state == StepStatus.PENDING) {
                throw new BusinessValidationException("步骤 " + step.getSeq() + " 尚未 START，不能回报结果");
            }
            if (state == StepStatus.DONE) {
                throw new ConflictException("STEP_ALREADY_DONE", "步骤 " + step.getSeq() + " 已完成");
            }
            if (state == StepStatus.FROZEN) {
                throw new ConflictException("STEP_FROZEN", "步骤 " + step.getSeq() + " 已冻结");
            }
        }
    }

    private void validateApplyRequest(ApplyRequest request) {
        Priority priority;
        try {
            priority = Priority.valueOf(request.priority());
        } catch (IllegalArgumentException e) {
            throw new BusinessValidationException("priority 必须为 HIGH/NORMAL/LOW");
        }
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new BusinessValidationException("windowEnd 必须晚于 windowStart（半开区间 [start,end)）");
        }
        Duration duration = Duration.between(request.windowStart(), request.windowEnd());
        if (duration.compareTo(Duration.ofHours(MAX_WINDOW_HOURS)) > 0) {
            throw new BusinessValidationException("割接窗口最长 4 小时，当前为 " + duration.toMinutes() + " 分钟");
        }
        if (!masterDataRepository.deviceExists(request.deviceCode())) {
            throw new BusinessValidationException("目标设备不存在: " + request.deviceCode());
        }
        List<ApplyRequest.StepSpec> specs = request.steps();
        boolean[] seqMask = new boolean[specs.size()];
        for (ApplyRequest.StepSpec spec : specs) {
            if (spec.seq() == null || spec.seq() < 1 || spec.seq() > specs.size()) {
                throw new BusinessValidationException("步骤 seq 必须在 1.." + specs.size() + " 内连续");
            }
            if (seqMask[spec.seq() - 1]) {
                throw new BusinessValidationException("步骤 seq 重复: " + spec.seq());
            }
            seqMask[spec.seq() - 1] = true;
            try {
                StepType.valueOf(spec.stepType());
            } catch (IllegalArgumentException e) {
                throw new BusinessValidationException("stepType 必须为 NORMAL/VERIFY，步骤 " + spec.seq());
            }
        }
    }

    private AnalysisResponse buildAnalysis(ApplicationPO app) {
        List<String> businesses = masterDataRepository.findBusinessCodesByDevice(app.getDeviceCode());
        List<ApplicationPO> overlaps = applicationRepository.findWindowHoldingOverlaps(
                app.getDeviceCode(), app.getWindowStart(), app.getWindowEnd(), app.getId());
        Priority self = Priority.valueOf(app.getPriority());
        List<AnalysisResponse.ConflictView> views = overlaps.stream().map(o ->
                new AnalysisResponse.ConflictView(o.getId(), o.getRequestId(), o.getPriority(),
                        o.getStatus(), o.getWindowStart(), o.getWindowEnd(),
                        Priority.valueOf(o.getPriority()).getRank() > self.getRank())).toList();
        return new AnalysisResponse(app.getId(), app.getStatus(), businesses, views);
    }

    private ApplicationPO lockApplication(Long applicationId) {
        ApplicationPO app = applicationRepository.lockById(applicationId);
        if (app == null) {
            throw new NotFoundException("割接申请不存在: " + applicationId);
        }
        return app;
    }

    private ApplicationResponse resolveApplyReplay(
            com.example.starter.cutover.po.IdempotencyRecordPO record, String fingerprint) {
        if (!record.getFingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_PARAM_MISMATCH",
                    "requestId=" + record.getRequestId() + " 已用于其他参数的申请，拒绝重放");
        }
        if (record.getResponseJson() == null) {
            // 行锁/唯一约束下仅在首个事务未完成时可能出现，短暂等待其结果
            awaitOutcome(record);
            record = idempotencyRepository.find(record.getRequestId(), SCOPE_APPLY).orElseThrow();
        }
        ApplicationResponse response = readJson(record.getResponseJson(), ApplicationResponse.class);
        return new ApplicationResponse(response.id(), response.requestId(), response.deviceCode(),
                response.title(), response.priority(), response.windowStart(), response.windowEnd(),
                response.status(), response.rejectReason(), response.steps(), true);
    }

    private ReportResponse resolveReportReplay(
            com.example.starter.cutover.po.IdempotencyRecordPO record, String fingerprint) {
        if (!record.getFingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_PARAM_MISMATCH",
                    "requestId=" + record.getRequestId() + " 已用于其他参数的回报，拒绝且不产生新步骤记录");
        }
        if (record.getResponseJson() == null) {
            awaitOutcome(record);
            record = idempotencyRepository.find(record.getRequestId(), SCOPE_STEP_REPORT).orElseThrow();
        }
        ReportResponse response = readJson(record.getResponseJson(), ReportResponse.class);
        return new ReportResponse(response.applicationId(), response.stepSeq(),
                response.stepStatus(), response.applicationStatus(),
                response.rollbackTriggered(), response.rollbackReason(),
                response.lateEvent(), true);
    }

    private void awaitOutcome(com.example.starter.cutover.po.IdempotencyRecordPO record) {
        for (int i = 0; i < 50; i++) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("IDEMPOTENCY_PENDING", "首次请求尚未完成");
            }
            var fresh = idempotencyRepository.find(record.getRequestId(), record.getScope());
            if (fresh.isPresent() && fresh.get().getResponseJson() != null) {
                return;
            }
        }
        throw new ConflictException("IDEMPOTENCY_PENDING", "首次请求结果暂不可用，请稍后重试");
    }

    private void storeReportOutcome(String requestId, Long stepId, ReportResponse response) {
        var record = idempotencyRepository.find(requestId, SCOPE_STEP_REPORT).orElseThrow();
        idempotencyRepository.updateOutcome(record.getId(),
                String.valueOf(stepId), writeJson(response));
    }

    private RollbackResponse terminalRollbackView(ApplicationPO app) {
        Optional<RollbackRecordPO> record = rollbackRecordRepository.findByCutoverId(app.getId());
        return record.map(r -> new RollbackResponse(app.getId(), app.getStatus(),
                r.getTriggerReason(), r.getDetail(), 0, true))
                .orElseGet(() -> new RollbackResponse(app.getId(), app.getStatus(), null,
                        "当前终态无回退记录", 0, false));
    }

    private ApplicationResponse loadApplicationResponse(long id, boolean replay) {
        ApplicationPO po = applicationRepository.findById(id).orElseThrow();
        return toResponse(po, stepRepository.findByCutoverId(id), replay);
    }

    private ApplicationResponse toResponse(ApplicationPO po, List<StepPO> steps, boolean replay) {
        List<ApplicationResponse.StepView> views = new ArrayList<>();
        for (StepPO s : steps) {
            views.add(new ApplicationResponse.StepView(s.getSeq(), s.getName(),
                    s.getStepType(), s.getStatus(), s.getStartedAt(), s.getCompletedAt()));
        }
        return new ApplicationResponse(po.getId(), po.getRequestId(), po.getDeviceCode(),
                po.getTitle(), po.getPriority(), po.getWindowStart(), po.getWindowEnd(),
                po.getStatus(), po.getRejectReason(), views, replay);
    }

    private List<Map<String, Object>> buildStepSnapshot(List<StepPO> steps) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (StepPO s : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", s.getSeq());
            item.put("name", s.getName());
            item.put("stepType", s.getStepType());
            item.put("status", s.getStatus());
            item.put("startedAt", s.getStartedAt());
            item.put("completedAt", s.getCompletedAt());
            snapshot.add(item);
        }
        return snapshot;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 步骤回报指纹载体：字段即全部业务要素。
     */
    private record StepReportFingerprint(
            long applicationId,
            int stepSeq,
            String requestId,
            String phase,
            String result
    ) {
    }
}
