package com.example.starter.cutover.service;

import com.example.starter.cutover.BusinessRuleException;
import com.example.starter.cutover.ConflictException;
import com.example.starter.cutover.EventType;
import com.example.starter.cutover.NotFoundException;
import com.example.starter.cutover.Priority;
import com.example.starter.cutover.RequestStatus;
import com.example.starter.cutover.StepStatus;
import com.example.starter.cutover.StepType;
import com.example.starter.cutover.dto.ActionResult;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.EventView;
import com.example.starter.cutover.dto.ExpireCheckRequest;
import com.example.starter.cutover.dto.RequestView;
import com.example.starter.cutover.dto.SealRequest;
import com.example.starter.cutover.dto.StartExecutionRequest;
import com.example.starter.cutover.dto.StepDefinition;
import com.example.starter.cutover.dto.StepReportRequest;
import com.example.starter.cutover.dto.StepView;
import com.example.starter.cutover.model.CutoverEventPO;
import com.example.starter.cutover.model.CutoverRequestPO;
import com.example.starter.cutover.model.CutoverStepPO;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.support.FingerprintUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 割接领域服务。
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>窗口冲突按半开区间 [start,end)，端点相接不冲突；同一设备同一时间只允许一个已批准割接；</li>
 *   <li>同窗口高优先级变更存在时，低优先级申请拒绝；高优先级新申请抢占低优先级在途申请；</li>
 *   <li>窗口最长 4 小时，恰 4 小时合法；</li>
 *   <li>关键验证点自该步开始起 10 分钟（恰满 10 分钟）未通过、或任一硬性检查失败，同一事务内
 *       下达回退指令并冻结后续步骤；</li>
 *   <li>窗口结束（atTime&gt;=end）仍有未完成步骤 -> WINDOW_EXPIRED 并强制回退；
 *       恰在窗口终点（reportedAt==end）完成的步骤有效；</li>
 *   <li>requestId/requestUid 幂等：指纹覆盖全部业务字段，同键同参返回首次结果，
 *       同键异参 409 且不产生新记录；</li>
 *   <li>回退与验证/封板依靠请求行 FOR UPDATE 串行化，终态唯一。</li>
 * </ul>
 */
@Service
public class CutoverService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ACTIVE_STATUS =
            Set.of(RequestStatus.APPROVED.name(), RequestStatus.EXECUTING.name());

    private final CutoverRepository repo;

    /** 窗口最大分钟数，默认 240（4 小时，恰达合法）。 */
    @Value("${cutover.max-window-minutes:240}")
    private long maxWindowMinutes;

    /** 关键验证点超时分钟数，默认 10（恰满判定超时）。 */
    @Value("${cutover.verification-timeout-minutes:10}")
    private long verificationTimeoutMinutes;

    public CutoverService(CutoverRepository repo) {
        this.repo = repo;
    }

    // ---------------- 申请 ----------------

    @Transactional
    public ActionResult create(CreateCutoverRequest req) {
        if (!repo.deviceExists(req.deviceCode())) {
            throw new BusinessRuleException("设备不存在: " + req.deviceCode());
        }
        if (!req.windowEnd().isAfter(req.windowStart())) {
            throw new BusinessRuleException("窗口结束时间必须晚于开始时间");
        }
        long minutes = Duration.between(req.windowStart(), req.windowEnd()).toMinutes();
        if (minutes > maxWindowMinutes) {
            throw new BusinessRuleException(
                    "割接窗口最长 " + maxWindowMinutes + " 分钟，当前 " + minutes + " 分钟");
        }

        String fp = fingerprintRequest(req);
        CutoverRequestPO existing = repo.findRequest(req.requestId());
        if (existing != null) {
            if (!existing.getFingerprint().equals(fp)) {
                throw new ConflictException("requestId=" + req.requestId()
                        + " 已存在但业务参数不一致（同键异参）");
            }
            return new ActionResult(true, "同键同参重放，返回首次结果", toView(existing));
        }

        // 锁住设备行，串行化同设备并发申请，保证重叠/优先级判定不出现幻读。
        repo.lockDevice(req.deviceCode());
        // 拿锁后复查：并发同 requestId 的首个事务可能已提交。
        CutoverRequestPO afterLock = repo.findRequest(req.requestId());
        if (afterLock != null) {
            if (!afterLock.getFingerprint().equals(fp)) {
                throw new ConflictException("requestId=" + req.requestId()
                        + " 已存在但业务参数不一致（同键异参）");
            }
            return new ActionResult(true, "同键同参重放，返回首次结果", toView(afterLock));
        }

        List<CutoverRequestPO> overlaps = repo.findOverlappingActive(
                req.deviceCode(), req.windowStart(), req.windowEnd(), req.requestId());
        Priority newPriority = req.priority();
        for (CutoverRequestPO overlap : overlaps) {
            Priority that = Priority.valueOf(overlap.getPriority());
            // 已进入执行的割接实际占用设备，任何新申请（含更高优先级）都拒绝；
            // 已批准待执行的同级/高级割接同样阻断本申请。
            if (RequestStatus.EXECUTING.name().equals(overlap.getStatus())
                    || that.ordinal() <= newPriority.ordinal()) {
                CutoverRequestPO rejected = buildNewRequest(req, fp, RequestStatus.REJECTED,
                        "窗口[" + req.windowStart() + "," + req.windowEnd()
                                + ")与已在途割接 " + overlap.getRequestId()
                                + "（优先级 " + that + "，状态 " + overlap.getStatus()
                                + "）冲突，优先级未占优，申请拒绝");
                repo.insertRequest(rejected);
                return new ActionResult(false,
                        "因同窗口更高/同级优先级割接在途，申请被拒绝", toView(rejected));
            }
        }
        // 本申请优先级严格更高：抢占拒绝所有重叠且尚待执行（APPROVED）的低优先级申请。
        for (CutoverRequestPO overlap : overlaps) {
            overlap.setStatus(RequestStatus.REJECTED.name());
            overlap.setRejectReason("被更高优先级新申请 " + req.requestId()
                    + "（优先级 " + newPriority + "）抢占拒绝");
            overlap.setUpdatedAt(LocalDateTime.now());
            repo.updateMutable(overlap);
        }

        LocalDateTime now = LocalDateTime.now();
        CutoverRequestPO po = buildNewRequest(req, fp, RequestStatus.SUBMITTED, null);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        repo.insertRequest(po);

        int seq = 1;
        for (StepDefinition sd : req.steps()) {
            if (sd.type() == StepType.ROLLBACK) {
                throw new BusinessRuleException("回退步骤由系统生成，申请中不允许指定 ROLLBACK 类型");
            }
            CutoverStepPO step = new CutoverStepPO();
            step.setRequestId(req.requestId());
            step.setSeqNo(seq++);
            step.setStepName(sd.name());
            step.setStepType(sd.type().name());
            step.setStatus(StepStatus.PENDING.name());
            step.setCreatedAt(now);
            step.setUpdatedAt(now);
            repo.insertStep(step);
        }
        return new ActionResult(false, "割接申请已受理，状态 SUBMITTED", toView(po));
    }

    // ---------------- 影响分析 ----------------

    @Transactional
    public ActionResult analyze(String requestId) {
        CutoverRequestPO po = mustLock(requestId);
        if (po.getImpactSystems() != null) {
            return new ActionResult(true, "影响分析已完成，结果可复算", toView(po));
        }
        if (!RequestStatus.SUBMITTED.name().equals(po.getStatus())) {
            throw new BusinessRuleException("当前状态 " + po.getStatus() + " 不允许执行影响分析");
        }
        List<String> systems = repo.findSystemsByDevice(po.getDeviceCode());
        po.setImpactSystems(String.join(",", systems));
        po.setStatus(RequestStatus.IMPACT_ANALYZED.name());
        po.setUpdatedAt(LocalDateTime.now());
        repo.updateMutable(po);
        return new ActionResult(false,
                "影响分析完成，命中 " + systems.size() + " 个关联业务系统", toView(po));
    }

    // ---------------- 审批 ----------------

    @Transactional
    public ActionResult approve(ApproveRequest req) {
        String fp = FingerprintUtil.sha256("APPROVE", req.requestId(),
                req.approved(), req.remark());
        CutoverEventPO existed = repo.findEvent(req.requestUid());
        if (existed != null) {
            return resolveIdempotent(existed, fp, req.requestId());
        }

        CutoverRequestPO po = mustLock(req.requestId());
        CutoverEventPO afterLock = repo.findEvent(req.requestUid());
        if (afterLock != null) {
            return resolveIdempotent(afterLock, fp, req.requestId());
        }
        String message;
        if (Boolean.TRUE.equals(req.approved())) {
            if (ACTIVE_STATUS.contains(po.getStatus())) {
                return new ActionResult(true, "该割接已处于批准/执行状态，幂等返回", toView(po));
            }
            if (RequestStatus.REJECTED.name().equals(po.getStatus())) {
                throw new BusinessRuleException("申请已被拒绝，不能审批通过");
            }
            if (!RequestStatus.IMPACT_ANALYZED.name().equals(po.getStatus())) {
                throw new BusinessRuleException("尚未完成影响分析，不能审批；当前状态 "
                        + po.getStatus());
            }
            // 审批前再次串行化并复核同设备窗口冲突。
            repo.lockDevice(po.getDeviceCode());
            List<CutoverRequestPO> overlaps = repo.findOverlappingActive(
                    po.getDeviceCode(), po.getWindowStart(), po.getWindowEnd(),
                    po.getRequestId());
            if (!overlaps.isEmpty()) {
                throw new BusinessRuleException("审批失败：同设备窗口存在在途割接 "
                        + overlaps.get(0).getRequestId());
            }
            po.setStatus(RequestStatus.APPROVED.name());
            message = "审批通过，状态 APPROVED";
        } else {
            if (RequestStatus.REJECTED.name().equals(po.getStatus())) {
                return new ActionResult(true, "该割接已是拒绝终态，幂等返回", toView(po));
            }
            if (ACTIVE_STATUS.contains(po.getStatus())
                    || RequestStatus.VERIFIED.name().equals(po.getStatus())
                    || RequestStatus.SEALED.name().equals(po.getStatus())) {
                throw new BusinessRuleException("割接已进入执行/终态，不能拒绝");
            }
            po.setStatus(RequestStatus.REJECTED.name());
            po.setRejectReason(req.remark() == null || req.remark().isBlank()
                    ? "审批拒绝" : req.remark());
            message = "审批拒绝，状态 REJECTED";
        }
        po.setUpdatedAt(LocalDateTime.now());
        repo.updateMutable(po);
        insertEvent(req.requestUid(), req.requestId(), EventType.APPROVE, null, fp,
                message, false);
        return new ActionResult(false, message, toView(po));
    }

    // ---------------- 开始窗口执行 ----------------

    @Transactional
    public ActionResult startExecution(StartExecutionRequest req) {
        String fp = FingerprintUtil.sha256("EXECUTE", req.requestId(), req.startTime());
        CutoverEventPO existed = repo.findEvent(req.requestUid());
        if (existed != null) {
            return resolveIdempotent(existed, fp, req.requestId());
        }

        CutoverRequestPO po = mustLock(req.requestId());
        CutoverEventPO afterLock = repo.findEvent(req.requestUid());
        if (afterLock != null) {
            return resolveIdempotent(afterLock, fp, req.requestId());
        }
        if (RequestStatus.EXECUTING.name().equals(po.getStatus())) {
            return new ActionResult(true, "割接已在执行中，幂等返回", toView(po));
        }
        if (!RequestStatus.APPROVED.name().equals(po.getStatus())) {
            throw new BusinessRuleException("只有 APPROVED 状态可开始执行，当前状态 "
                    + po.getStatus());
        }
        if (req.startTime().isBefore(po.getWindowStart())
                || req.startTime().isAfter(po.getWindowEnd())) {
            throw new BusinessRuleException("执行开始时间必须落在窗口 [start,end] 内");
        }
        po.setStatus(RequestStatus.EXECUTING.name());
        po.setExecStartedAt(req.startTime());
        po.setUpdatedAt(LocalDateTime.now());
        repo.updateMutable(po);
        String message = "进入窗口执行，执行开始时间 " + req.startTime();
        insertEvent(req.requestUid(), req.requestId(), EventType.EXECUTE_START, null, fp,
                message, false);
        return new ActionResult(false, message, toView(po));
    }

    // ---------------- 步骤逐条回报 ----------------

    @Transactional
    public ActionResult report(StepReportRequest req) {
        String fp = FingerprintUtil.sha256("REPORT", req.requestId(), req.seqNo(),
                req.success(), req.reportedAt(), req.remark());
        CutoverEventPO existed = repo.findEvent(req.requestUid());
        if (existed != null) {
            return resolveIdempotent(existed, fp, req.requestId());
        }

        // 同一事务内锁定申请行与全部步骤行，回退/验证/封板竞态在此串行化。
        CutoverRequestPO po = mustLock(req.requestId());
        List<CutoverStepPO> steps = repo.lockSteps(req.requestId());
        CutoverEventPO afterLock = repo.findEvent(req.requestUid());
        if (afterLock != null) {
            return resolveIdempotent(afterLock, fp, req.requestId());
        }

        // 封板后补报：只记迟到事件，不改任何步骤/申请状态。
        if (RequestStatus.SEALED.name().equals(po.getStatus())) {
            insertEvent(req.requestUid(), req.requestId(), EventType.LATE_REPORT,
                    req.seqNo(), fp, "封板后补报步骤，记为迟到事件", true);
            return new ActionResult(false, "割接已封板，补报仅记录为迟到事件", toView(po));
        }
        // 已回退终态：步骤已冻结，拒绝任何新回报。
        if (RequestStatus.ROLLED_BACK.name().equals(po.getStatus())
                || RequestStatus.WINDOW_EXPIRED.name().equals(po.getStatus())) {
            throw new BusinessRuleException("割接已因 " + po.getRollbackCause()
                    + " 回退终态，步骤已冻结，不能继续回报");
        }
        if (!RequestStatus.EXECUTING.name().equals(po.getStatus())) {
            throw new BusinessRuleException("只有 EXECUTING 状态可回报步骤，当前状态 "
                    + po.getStatus());
        }

        CutoverStepPO target = steps.stream()
                .filter(s -> s.getSeqNo().equals(req.seqNo()))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "步骤不存在: seqNo=" + req.seqNo()));
        if (StepStatus.FROZEN.name().equals(target.getStatus())) {
            throw new BusinessRuleException("步骤已被回退指令冻结，不能回报");
        }
        if (StepStatus.SUCCESS.name().equals(target.getStatus())) {
            throw new BusinessRuleException("步骤 seqNo=" + req.seqNo() + " 已成功回报");
        }
        CutoverStepPO expected = steps.stream()
                .filter(s -> StepStatus.PENDING.name().equals(s.getStatus())
                        || StepStatus.RUNNING.name().equals(s.getStatus()))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("没有可回报的在途步骤"));
        if (!expected.getSeqNo().equals(target.getSeqNo())) {
            throw new BusinessRuleException("步骤必须按序回报，下一个待回报步骤 seqNo="
                    + expected.getSeqNo());
        }
        if (req.reportedAt().isBefore(po.getWindowStart())) {
            throw new BusinessRuleException("回报时间早于窗口开始时间");
        }

        LocalDateTime startedAt = target.getSeqNo() == 1
                ? po.getExecStartedAt()
                : steps.stream()
                    .filter(s -> s.getSeqNo() == target.getSeqNo() - 1)
                    .map(CutoverStepPO::getReportedAt).findFirst().orElse(null);
        LocalDateTime now = LocalDateTime.now();
        target.setStartedAt(startedAt);

        // 窗口口径：reportedAt > windowEnd 才属超期；恰在窗口终点完成有效。
        boolean pastWindow = req.reportedAt().isAfter(po.getWindowEnd());

        if (pastWindow) {
            target.setStatus(StepStatus.FAILED.name());
            target.setReportedAt(req.reportedAt());
            target.setUpdatedAt(now);
            persistStep(target);
            String message = "回报时间 " + req.reportedAt() + " 已晚于窗口终点 "
                    + po.getWindowEnd() + "，强制回退";
            triggerRollback(po, steps, "WINDOW_EXPIRED", req.seqNo(), req.reportedAt());
            insertEvent(req.requestUid(), req.requestId(), EventType.STEP_REPORT,
                    req.seqNo(), fp, message, false);
            return new ActionResult(false, message, toView(repo.lockRequest(req.requestId())));
        }

        if (Boolean.FALSE.equals(req.success())) {
            String cause = switch (StepType.valueOf(target.getStepType())) {
                case HARDCHECK -> "HARDCHECK_FAILED";
                case CRITICAL -> "CRITICAL_FAILED";
                default -> "STEP_FAILED";
            };
            target.setStatus(StepStatus.FAILED.name());
            target.setReportedAt(req.reportedAt());
            target.setUpdatedAt(now);
            persistStep(target);
            String message = "步骤 seqNo=" + req.seqNo() + " 类型 " + target.getStepType()
                    + " 失败，命中回退判据 " + cause;
            triggerRollback(po, steps, cause, req.seqNo(), req.reportedAt());
            insertEvent(req.requestUid(), req.requestId(), EventType.STEP_REPORT,
                    req.seqNo(), fp, message, false);
            return new ActionResult(false, message, toView(repo.lockRequest(req.requestId())));
        }

        // success=true：关键验证点还要判定 10 分钟超时（恰满 10 分钟即超时）。
        if (StepType.CRITICAL.name().equals(target.getStepType()) && startedAt != null) {
            long elapsed = Duration.between(startedAt, req.reportedAt()).toMinutes();
            if (elapsed >= verificationTimeoutMinutes) {
                target.setStatus(StepStatus.FAILED.name());
                target.setReportedAt(req.reportedAt());
                target.setUpdatedAt(now);
                persistStep(target);
                String message = "关键验证点 seqNo=" + req.seqNo() + " 用时 " + elapsed
                        + " 分钟，恰满/超过 " + verificationTimeoutMinutes
                        + " 分钟超时阈值，自动回退";
                triggerRollback(po, steps, "CRITICAL_TIMEOUT", req.seqNo(), req.reportedAt());
                insertEvent(req.requestUid(), req.requestId(), EventType.STEP_REPORT,
                        req.seqNo(), fp, message, false);
                return new ActionResult(false, message,
                        toView(repo.lockRequest(req.requestId())));
            }
        }

        target.setStatus(StepStatus.SUCCESS.name());
        target.setReportedAt(req.reportedAt());
        target.setUpdatedAt(now);
        persistStep(target);

        boolean allSuccess = repo.lockSteps(req.requestId()).stream()
                .allMatch(s -> StepStatus.SUCCESS.name().equals(s.getStatus()));
        String message;
        if (allSuccess) {
            po.setStatus(RequestStatus.VERIFIED.name());
            po.setUpdatedAt(now);
            repo.updateMutable(po);
            message = "步骤 seqNo=" + req.seqNo() + " 成功，全部步骤完成，验证通过 VERIFIED";
        } else {
            message = "步骤 seqNo=" + req.seqNo() + " 成功，等待后续步骤回报";
        }
        insertEvent(req.requestUid(), req.requestId(), EventType.STEP_REPORT,
                req.seqNo(), fp, message, false);
        return new ActionResult(false, message, toView(repo.lockRequest(req.requestId())));
    }

    // ---------------- 窗口超期巡检 ----------------

    @Transactional
    public ActionResult expireCheck(ExpireCheckRequest req) {
        String fp = FingerprintUtil.sha256("EXPIRE", req.requestId(), req.atTime());
        CutoverEventPO existed = repo.findEvent(req.requestUid());
        if (existed != null) {
            return resolveIdempotent(existed, fp, req.requestId());
        }

        CutoverRequestPO po = mustLock(req.requestId());
        List<CutoverStepPO> steps = repo.lockSteps(req.requestId());
        CutoverEventPO afterLock = repo.findEvent(req.requestUid());
        if (afterLock != null) {
            return resolveIdempotent(afterLock, fp, req.requestId());
        }

        String status = po.getStatus();
        if (RequestStatus.SEALED.name().equals(status)
                || RequestStatus.ROLLED_BACK.name().equals(status)
                || RequestStatus.WINDOW_EXPIRED.name().equals(status)
                || RequestStatus.REJECTED.name().equals(status)) {
            String message = "割接已处于终态 " + status + "，巡检幂等返回";
            insertEvent(req.requestUid(), req.requestId(), EventType.EXPIRE_CHECK, null, fp,
                    message, false);
            return new ActionResult(false, message, toView(po));
        }
        if (RequestStatus.VERIFIED.name().equals(status)) {
            String message = "割接已验证通过，窗口正常收口，无需强制回退";
            insertEvent(req.requestUid(), req.requestId(), EventType.EXPIRE_CHECK, null, fp,
                    message, false);
            return new ActionResult(false, message, toView(po));
        }

        // 关键验证点超时巡检：当前在途步骤为 CRITICAL 且自其开始（首步=执行开始，
        // 其余=上一步完成时间）恰满/超过 10 分钟仍未通过 -> CRITICAL_TIMEOUT 自动回退。
        CutoverStepPO current = steps.stream()
                .filter(s -> StepStatus.PENDING.name().equals(s.getStatus())
                        || StepStatus.RUNNING.name().equals(s.getStatus()))
                .findFirst().orElse(null);
        if (current != null && StepType.CRITICAL.name().equals(current.getStepType())) {
            LocalDateTime criticalStart = current.getSeqNo() == 1
                    ? po.getExecStartedAt()
                    : steps.stream()
                        .filter(s -> s.getSeqNo() == current.getSeqNo() - 1)
                        .map(CutoverStepPO::getReportedAt).findFirst().orElse(null);
            if (criticalStart != null
                    && !req.atTime().isBefore(criticalStart.plusMinutes(verificationTimeoutMinutes))) {
                String m = "巡检时间 " + req.atTime() + " 关键验证点 seqNo=" + current.getSeqNo()
                        + " 自 " + criticalStart + " 起恰满/超过 " + verificationTimeoutMinutes
                        + " 分钟未通过，自动回退";
                triggerRollback(po, steps, "CRITICAL_TIMEOUT", current.getSeqNo(), req.atTime());
                insertEvent(req.requestUid(), req.requestId(), EventType.EXPIRE_CHECK, null, fp,
                        m, false);
                return new ActionResult(false, m, toView(repo.lockRequest(req.requestId())));
            }
        }

        if (req.atTime().isBefore(po.getWindowEnd())) {
            String message = "窗口尚未结束（巡检时间 " + req.atTime() + " < 窗口终点 "
                    + po.getWindowEnd() + "）";
            insertEvent(req.requestUid(), req.requestId(), EventType.EXPIRE_CHECK, null, fp,
                    message, false);
            return new ActionResult(false, message, toView(po));
        }

        int unfinished = repo.countUnfinishedSteps(req.requestId());
        if (unfinished == 0) {
            String message = "恰在窗口终点全部步骤已完成，步骤有效，不判定超期";
            insertEvent(req.requestUid(), req.requestId(), EventType.EXPIRE_CHECK, null, fp,
                    message, false);
            return new ActionResult(false, message, toView(po));
        }

        String m = "窗口结束时间 " + po.getWindowEnd() + " 仍有 " + unfinished
                + " 个未完成步骤，转 WINDOW_EXPIRED 并强制回退";
        triggerRollback(po, steps, "WINDOW_EXPIRED", null, req.atTime());
        insertEvent(req.requestUid(), req.requestId(), EventType.EXPIRE_CHECK, null, fp,
                m, false);
        return new ActionResult(false, m, toView(repo.lockRequest(req.requestId())));
    }

    // ---------------- 封板 ----------------

    @Transactional
    public ActionResult seal(SealRequest req) {
        String fp = FingerprintUtil.sha256("SEAL", req.requestId());
        CutoverEventPO existed = repo.findEvent(req.requestUid());
        if (existed != null) {
            return resolveIdempotent(existed, fp, req.requestId());
        }

        CutoverRequestPO po = mustLock(req.requestId());
        CutoverEventPO afterLock = repo.findEvent(req.requestUid());
        if (afterLock != null) {
            return resolveIdempotent(afterLock, fp, req.requestId());
        }
        if (RequestStatus.SEALED.name().equals(po.getStatus())) {
            // 不同 requestUid 的重复封板同样幂等成功。
            insertEvent(req.requestUid(), req.requestId(), EventType.SEAL, null, fp,
                    "割接已封板，重复封板幂等成功", false);
            return new ActionResult(true, "割接已封板，重复封板幂等成功", toView(po));
        }
        if (RequestStatus.ROLLED_BACK.name().equals(po.getStatus())
                || RequestStatus.WINDOW_EXPIRED.name().equals(po.getStatus())) {
            throw new BusinessRuleException("割接已回退（" + po.getRollbackCause()
                    + "），终态唯一，不能封板");
        }
        if (!RequestStatus.VERIFIED.name().equals(po.getStatus())) {
            throw new BusinessRuleException("只有 VERIFIED 状态可以封板，当前状态 "
                    + po.getStatus());
        }
        po.setStatus(RequestStatus.SEALED.name());
        po.setUpdatedAt(LocalDateTime.now());
        repo.updateMutable(po);
        insertEvent(req.requestUid(), req.requestId(), EventType.SEAL, null, fp,
                "验证封板完成，状态 SEALED", false);
        return new ActionResult(false, "验证封板完成，状态 SEALED", toView(po));
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public RequestView get(String requestId) {
        CutoverRequestPO po = repo.findRequest(requestId);
        if (po == null) {
            throw new NotFoundException("割接申请不存在: " + requestId);
        }
        return toView(po);
    }

    @Transactional(readOnly = true)
    public List<EventView> events(String requestId) {
        if (repo.findRequest(requestId) == null) {
            throw new NotFoundException("割接申请不存在: " + requestId);
        }
        return repo.listEvents(requestId).stream()
                .map(e -> new EventView(e.getRequestUid(), e.getRequestId(),
                        e.getEventType(), e.getSeqNo(),
                        Boolean.TRUE.equals(e.getLateFlag()), e.getCreatedAt()))
                .toList();
    }

    // ---------------- 内部方法 ----------------

    /**
     * 在同一事务内下达回退指令：冻结所有未完成步骤、追加系统回退步骤、置唯一回退终态、
     * 记录回退判据命中。调用方必须已持有申请行与步骤行锁。
     */
    private void triggerRollback(CutoverRequestPO po, List<CutoverStepPO> steps,
                                 String cause, Integer triggerSeq, LocalDateTime at) {
        repo.freezeUnfinishedSteps(po.getRequestId());

        int maxSeq = steps.stream().mapToInt(CutoverStepPO::getSeqNo).max().orElse(0);
        LocalDateTime now = LocalDateTime.now();
        CutoverStepPO rollbackStep = new CutoverStepPO();
        rollbackStep.setRequestId(po.getRequestId());
        rollbackStep.setSeqNo(maxSeq + 1);
        rollbackStep.setStepName("系统自动回退（判据: " + cause + "）");
        rollbackStep.setStepType(StepType.ROLLBACK.name());
        rollbackStep.setStatus(StepStatus.SUCCESS.name());
        rollbackStep.setStartedAt(at);
        rollbackStep.setReportedAt(at);
        rollbackStep.setCreatedAt(now);
        rollbackStep.setUpdatedAt(now);
        repo.insertStep(rollbackStep);

        po.setRollbackCause(cause);
        po.setStatus("WINDOW_EXPIRED".equals(cause)
                ? RequestStatus.WINDOW_EXPIRED.name() : RequestStatus.ROLLED_BACK.name());
        po.setUpdatedAt(now);
        repo.updateMutable(po);

        // 回退判据命中记录（幂等，便于重入/复算）。
        String triggerUid = "SYS-ROLLBACK-" + po.getRequestId() + "-" + cause;
        if (repo.findEvent(triggerUid) == null) {
            insertEvent(triggerUid, po.getRequestId(), EventType.ROLLBACK_TRIGGER,
                    triggerSeq,
                    FingerprintUtil.sha256("ROLLBACK", po.getRequestId(), cause, triggerSeq),
                    "命中回退判据: " + cause, false);
        }
    }

    private void persistStep(CutoverStepPO step) {
        repo.updateStepState(step.getRequestId(), step.getSeqNo(), step.getStatus(),
                step.getStartedAt(), step.getReportedAt());
    }

    private CutoverRequestPO mustLock(String requestId) {
        CutoverRequestPO po = repo.lockRequest(requestId);
        if (po == null) {
            throw new NotFoundException("割接申请不存在: " + requestId);
        }
        return po;
    }

    private void insertEvent(String uid, String requestId, EventType type, Integer seqNo,
                             String fingerprint, String message, boolean late) {
        CutoverEventPO po = new CutoverEventPO();
        po.setRequestUid(uid);
        po.setRequestId(requestId);
        po.setEventType(type.name());
        po.setSeqNo(seqNo);
        po.setPayloadFingerprint(fingerprint);
        po.setResultSnapshot(writeSnapshot(message));
        po.setLateFlag(late);
        po.setCreatedAt(LocalDateTime.now());
        try {
            repo.insertEvent(po);
        } catch (DuplicateKeyException dup) {
            // 并发下同 uid 首事件已提交：交由调用方外层按幂等/冲突处理。
            CutoverEventPO winner = repo.findEvent(uid);
            if (winner != null && !winner.getPayloadFingerprint().equals(fingerprint)) {
                throw new ConflictException("requestUid=" + uid
                        + " 已存在但业务参数不一致（同键异参）");
            }
        }
    }

    private ActionResult resolveIdempotent(CutoverEventPO existed, String fp, String requestId) {
        if (!existed.getPayloadFingerprint().equals(fp)) {
            throw new ConflictException("requestUid=" + existed.getRequestUid()
                    + " 已存在但业务参数不一致（同键异参），拒绝产生新记录");
        }
        String message = readSnapshotMessage(existed.getResultSnapshot());
        CutoverRequestPO fresh = repo.lockRequest(requestId);
        return new ActionResult(true, "同键同参重放，返回首次结果：" + message,
                fresh == null ? null : toView(fresh));
    }

    private CutoverRequestPO buildNewRequest(CreateCutoverRequest req, String fp,
                                             RequestStatus status, String rejectReason) {
        LocalDateTime now = LocalDateTime.now();
        CutoverRequestPO po = new CutoverRequestPO();
        po.setRequestId(req.requestId());
        po.setTitle(req.title());
        po.setDeviceCode(req.deviceCode());
        po.setPriority(req.priority().name());
        po.setWindowStart(req.windowStart());
        po.setWindowEnd(req.windowEnd());
        po.setStatus(status.name());
        po.setFingerprint(fp);
        po.setRejectReason(rejectReason);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }

    private String fingerprintRequest(CreateCutoverRequest req) {
        List<Object> parts = new ArrayList<>();
        parts.add("CREATE");
        parts.add(req.requestId());
        parts.add(req.title());
        parts.add(req.deviceCode());
        parts.add(req.priority());
        parts.add(req.windowStart());
        parts.add(req.windowEnd());
        for (StepDefinition s : req.steps()) {
            parts.add(s.name());
            parts.add(s.type());
        }
        return FingerprintUtil.sha256(parts.toArray());
    }

    private RequestView toView(CutoverRequestPO po) {
        List<CutoverStepPO> steps = repo.listSteps(po.getRequestId());
        List<StepView> stepViews = steps.stream()
                .map(s -> new StepView(s.getSeqNo(), s.getStepName(), s.getStepType(),
                        s.getStatus(), s.getStartedAt(), s.getReportedAt()))
                .toList();
        List<String> impacts = po.getImpactSystems() == null
                ? List.of()
                : Arrays.stream(po.getImpactSystems().split(","))
                    .filter(s -> !s.isEmpty()).toList();
        return new RequestView(po.getRequestId(), po.getTitle(), po.getDeviceCode(),
                po.getPriority(), po.getWindowStart(), po.getWindowEnd(), po.getStatus(),
                po.getImpactSystems() == null ? null : impacts,
                po.getRejectReason(), po.getRollbackCause(), stepViews);
    }

    private String writeSnapshot(String message) {
        try {
            return JSON.writeValueAsString(java.util.Map.of("message", message));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String readSnapshotMessage(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return "";
        }
        try {
            return JSON.readValue(snapshot, new TypeReference<java.util.Map<String, String>>() {
            }).getOrDefault("message", "");
        } catch (Exception e) {
            return "";
        }
    }
}
