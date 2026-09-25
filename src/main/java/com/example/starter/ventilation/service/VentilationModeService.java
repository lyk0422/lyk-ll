package com.example.starter.ventilation.service;

import com.example.starter.ventilation.CommandStatus;
import com.example.starter.ventilation.DeviceAction;
import com.example.starter.ventilation.DeviceType;
import com.example.starter.ventilation.ModeStatus;
import com.example.starter.ventilation.MutableClock;
import com.example.starter.ventilation.RequestType;
import com.example.starter.ventilation.VentilationProperties;
import com.example.starter.ventilation.domain.DeviceCommandRow;
import com.example.starter.ventilation.domain.IdemRequestRow;
import com.example.starter.ventilation.domain.ModeSessionRow;
import com.example.starter.ventilation.repo.DeviceCommandRepository;
import com.example.starter.ventilation.repo.DeviceFeedbackRepository;
import com.example.starter.ventilation.repo.EventLogRepository;
import com.example.starter.ventilation.repo.IdempotencyRepository;
import com.example.starter.ventilation.repo.MasterDataRepository;
import com.example.starter.ventilation.repo.ModeSessionRepository;
import com.example.starter.ventilation.service.PlanGenerator.PlannedCommand;
import com.example.starter.ventilation.web.CommandView;
import com.example.starter.ventilation.web.FeedbackResponse;
import com.example.starter.ventilation.web.ModeResponse;
import com.example.starter.ventilation.web.SessionView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 隧道火灾通风联动核心服务。
 *
 * <p>所有激活/复位/归档/反馈均以 requestId 幂等；同一分区靠 zone_activation_lock
 * 主键约束保证并发唯一激活；共享风阀以先激活分区目标状态为准。</p>
 */
@Service
public class VentilationModeService {

    private static final Logger log = LoggerFactory.getLogger(VentilationModeService.class);

    /** 激活阶段。 */
    public static final String PHASE_ACTIVATE = "ACTIVATE";
    /** 复位阶段。 */
    public static final String PHASE_RESET = "RESET";

    private static final String OUTCOME_ACTIVATED = "ACTIVATED";
    private static final String OUTCOME_ACTIVATED_WITH_CONFLICT = "ACTIVATED_WITH_SHARED_DAMPER_CONFLICT";
    private static final String OUTCOME_RESET = "RESET_COMPLETED";
    private static final String OUTCOME_RESETTING = "RESET_IN_PROGRESS";
    private static final String OUTCOME_ALREADY_RESET = "ALREADY_RESET_COMPLETE";
    private static final String OUTCOME_ARCHIVED = "ARCHIVED";
    private static final String OUTCOME_ALREADY_ARCHIVED = "ALREADY_ARCHIVED";
    private static final Set<String> SMOKE_DIRECTIONS = Set.of("FORWARD", "BACKWARD");
    private static final Set<String> TARGET_CONDITIONS = Set.of("SMOKE_EXHAUST", "PRESSURIZATION");
    private static final String IN_POSITION = "IN_POSITION";

    private final MasterDataRepository masterData;
    private final ModeSessionRepository sessions;
    private final DeviceCommandRepository commands;
    private final DeviceFeedbackRepository feedbacks;
    private final EventLogRepository events;
    private final IdempotencyRepository idempotency;
    private final PlanGenerator planGenerator;
    private final MutableClock clock;
    private final VentilationProperties properties;
    private final ObjectMapper objectMapper;

    public VentilationModeService(MasterDataRepository masterData,
                                  ModeSessionRepository sessions,
                                  DeviceCommandRepository commands,
                                  DeviceFeedbackRepository feedbacks,
                                  EventLogRepository events,
                                  IdempotencyRepository idempotency,
                                  PlanGenerator planGenerator,
                                  MutableClock clock,
                                  VentilationProperties properties,
                                  ObjectMapper objectMapper) {
        this.masterData = masterData;
        this.sessions = sessions;
        this.commands = commands;
        this.feedbacks = feedbacks;
        this.events = events;
        this.idempotency = idempotency;
        this.planGenerator = planGenerator;
        this.clock = clock;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 火灾模式激活：生成先阀后机指令序列并立即下发全部风阀指令。
     */
    @Transactional
    public ModeResponse activate(String requestId, String zoneCode,
                                 String smokeDirection, String targetCondition) {
        validateZone(zoneCode);
        if (!SMOKE_DIRECTIONS.contains(smokeDirection)) {
            throw ApiException.badRequest("INVALID_SMOKE_DIRECTION",
                    "smokeDirection 仅支持 FORWARD / BACKWARD");
        }
        if (!TARGET_CONDITIONS.contains(targetCondition)) {
            throw ApiException.badRequest("INVALID_TARGET_CONDITION",
                    "targetCondition 仅支持 SMOKE_EXHAUST / PRESSURIZATION");
        }
        String fingerprint = Fingerprints.of(requestId, RequestType.ACTIVATE.name(),
                zoneCode, smokeDirection, targetCondition);
        IdemRequestRow existing = idempotency.find(requestId);
        if (existing != null) {
            return handleIdemReplay(existing, fingerprint);
        }

        long now = clock.nowMillis();
        // 抢占 requestId 首次处理权；并发同键者转重放。
        if (!idempotency.insert(requestId, RequestType.ACTIVATE.name(), zoneCode, fingerprint,
                "SUCCESS", null, null, now)) {
            return handleIdemReplay(idempotency.find(requestId), fingerprint);
        }

        // 同一分区同一时刻只允许一个激活/复位中的火灾模式（复位完成后锁已释放）。
        if (!sessions.tryAcquireZoneLock(zoneCode, -1L, now)) {
            ModeResponse conflict = modeResponse(false, "ZONE_BUSY",
                    "分区 " + zoneCode + " 存在激活中或复位中的火灾模式，并发激活仅一个成功",
                    sessions.findLatestByZone(zoneCode), List.of());
            idempotency.updateResult(requestId, "CONFLICT", conflict.conflictReason(),
                    writeJson(conflict));
            return conflict;
        }

        ModeSessionRow session = sessions.insert(zoneCode, requestId, smokeDirection,
                targetCondition, now);
        sessions.updateLockSession(zoneCode, session.getId());
        events.append("MODE_ACTIVATED", session.getId(), zoneCode, requestId,
                "烟流假设=" + smokeDirection + "，目标工况=" + targetCondition, now);

        List<PlannedCommand> plan = planGenerator.activatePlan(zoneCode, smokeDirection);
        List<String> notes = new ArrayList<>();
        String conflictReason = null;
        for (PlannedCommand planned : plan) {
            long commandId = commands.insert(session.getId(), requestId, zoneCode,
                    planned.deviceCode(), planned.deviceType(), planned.action(),
                    PHASE_ACTIVATE, planned.seqNo());
            DeviceCommandRow row = commands.findById(commandId);
            if (planned.shared()) {
                if (sessions.tryCommitSharedDamper(planned.deviceCode(), planned.action().name(),
                        session.getId(), zoneCode, now)) {
                    notes.add("共享风阀 " + planned.deviceCode() + " 由本分区先占用，目标="
                            + planned.action());
                } else {
                    ModeSessionRepository.SharedCommitment commitment =
                            sessions.findSharedCommitment(planned.deviceCode());
                    if (commitment != null && commitment.targetAction().equals(planned.action().name())) {
                        commands.markTerminal(row.getId(), CommandStatus.ALIGNED, now);
                        notes.add("共享风阀 " + planned.deviceCode() + " 已由先激活分区 "
                                + commitment.zoneCode() + " 调整为相同目标 " + planned.action()
                                + "，本分区不重复下发");
                    } else {
                        commands.markTerminal(row.getId(), CommandStatus.CONFLICT, now);
                        String priorZone = commitment == null ? "其他分区" : commitment.zoneCode();
                        String priorAction = commitment == null ? "未知" : commitment.targetAction();
                        conflictReason = "共享风阀 " + planned.deviceCode() + " 已被先激活分区 "
                                + priorZone + " 占用，目标状态=" + priorAction
                                + "，以先激活分区为准；本分区期望=" + planned.action();
                        notes.add("冲突：" + conflictReason);
                        events.append("CONFLICT", session.getId(), zoneCode, requestId,
                                conflictReason, now);
                    }
                }
            }
        }

        // 先阀后机：激活时立即下发全部风阀指令，风机须等风阀全部到位后才下发。
        dispatchEligible(session.getId(), PHASE_ACTIVATE, requestId, now);

        String outcome = conflictReason == null
                ? OUTCOME_ACTIVATED : OUTCOME_ACTIVATED_WITH_CONFLICT;
        ModeResponse response = modeResponse(false, outcome, conflictReason,
                sessions.findById(session.getId()), notes);
        idempotency.updateResult(requestId, "SUCCESS", conflictReason, writeJson(response));
        return response;
    }

    /**
     * 火警解除复位：按激活设备逆序生成指令（先停风机、到位后关风阀）。
     */
    @Transactional
    public ModeResponse reset(String requestId, String zoneCode) {
        validateZone(zoneCode);
        String fingerprint = Fingerprints.of(requestId, RequestType.RESET.name(), zoneCode);
        IdemRequestRow existing = idempotency.find(requestId);
        if (existing != null) {
            return handleIdemReplay(existing, fingerprint);
        }
        long now = clock.nowMillis();
        if (!idempotency.insert(requestId, RequestType.RESET.name(), zoneCode, fingerprint,
                "SUCCESS", null, null, now)) {
            return handleIdemReplay(idempotency.find(requestId), fingerprint);
        }

        ModeSessionRow latest = sessions.findLatestByZone(zoneCode);
        if (latest == null) {
            ModeResponse resp = modeResponse(false, "NO_ACTIVE_MODE",
                    "分区 " + zoneCode + " 不存在可复位的火灾模式", null, List.of());
            idempotency.updateResult(requestId, "CONFLICT", resp.conflictReason(), writeJson(resp));
            return resp;
        }
        if (latest.getStatus() == ModeStatus.ARCHIVED) {
            ModeResponse resp = modeResponse(false, OUTCOME_ALREADY_ARCHIVED,
                    "分区 " + zoneCode + " 的模式已归档，无需复位", latest, List.of());
            idempotency.updateResult(requestId, "SUCCESS", null, writeJson(resp));
            return resp;
        }
        if (latest.getStatus() == ModeStatus.RESET_COMPLETE) {
            ModeResponse resp = modeResponse(false, OUTCOME_ALREADY_RESET,
                    "分区 " + zoneCode + " 已完成复位", latest, List.of());
            idempotency.updateResult(requestId, "SUCCESS", null, writeJson(resp));
            return resp;
        }
        if (latest.getStatus() == ModeStatus.RESETTING) {
            ModeResponse resp = modeResponse(false, "RESET_IN_PROGRESS",
                    "分区 " + zoneCode + " 正在复位中，复位完成前不接受重复复位", latest, List.of());
            idempotency.updateResult(requestId, "CONFLICT", resp.conflictReason(), writeJson(resp));
            return resp;
        }

        sessions.updateStatus(latest.getId(), ModeStatus.RESETTING);
        events.append("MODE_RESETTING", latest.getId(), zoneCode, requestId,
                "火警解除，按逆序复位", now);

        List<DeviceCommandRow> activateCommands =
                commands.findBySessionAndPhase(latest.getId(), PHASE_ACTIVATE);
        List<PlannedCommand> plan = planGenerator.resetPlan(activateCommands);
        for (PlannedCommand planned : plan) {
            commands.insert(latest.getId(), requestId, zoneCode, planned.deviceCode(),
                    planned.deviceType(), planned.action(), PHASE_RESET, planned.seqNo());
        }
        // 逆序：复位时立即下发风机 STOP，风阀 CLOSE 须等风机停稳到位。
        dispatchEligible(latest.getId(), PHASE_RESET, requestId, now);

        ModeResponse response = checkResetComplete(latest.getId(), requestId, now);
        idempotency.updateResult(requestId, "SUCCESS", null, writeJson(response));
        return response;
    }

    /**
     * 模式归档（终态）：仅复位完成后可归档；重复归档幂等成功。
     */
    @Transactional
    public ModeResponse archive(String requestId, String zoneCode) {
        validateZone(zoneCode);
        String fingerprint = Fingerprints.of(requestId, RequestType.ARCHIVE.name(), zoneCode);
        IdemRequestRow existing = idempotency.find(requestId);
        if (existing != null) {
            return handleIdemReplay(existing, fingerprint);
        }
        long now = clock.nowMillis();
        if (!idempotency.insert(requestId, RequestType.ARCHIVE.name(), zoneCode, fingerprint,
                "SUCCESS", null, null, now)) {
            return handleIdemReplay(idempotency.find(requestId), fingerprint);
        }

        ModeSessionRow latest = sessions.findLatestByZone(zoneCode);
        if (latest == null) {
            ModeResponse resp = modeResponse(false, "MODE_NOT_FOUND",
                    "分区 " + zoneCode + " 不存在火灾模式", null, List.of());
            idempotency.updateResult(requestId, "CONFLICT", resp.conflictReason(), writeJson(resp));
            return resp;
        }
        if (latest.getStatus() == ModeStatus.ARCHIVED) {
            ModeResponse resp = modeResponse(false, OUTCOME_ALREADY_ARCHIVED,
                    "分区 " + zoneCode + " 的模式已归档（终态幂等）", latest, List.of());
            idempotency.updateResult(requestId, "SUCCESS", null, writeJson(resp));
            return resp;
        }
        if (latest.getStatus() != ModeStatus.RESET_COMPLETE) {
            ModeResponse resp = modeResponse(false, "MODE_NOT_RESET_COMPLETE",
                    "分区 " + zoneCode + " 复位完成前不可归档", latest, List.of());
            idempotency.updateResult(requestId, "CONFLICT", resp.conflictReason(), writeJson(resp));
            return resp;
        }

        sessions.markArchived(latest.getId(), now);
        sessions.releaseSharedDampers(latest.getId());
        sessions.releaseZoneLock(zoneCode);
        events.append("MODE_ARCHIVED", latest.getId(), zoneCode, requestId,
                "模式归档，进入终态", now);

        ModeResponse response = modeResponse(false, OUTCOME_ARCHIVED, null,
                sessions.findById(latest.getId()), List.of("归档为终态，迟到反馈只记事件"));
        idempotency.updateResult(requestId, "SUCCESS", null, writeJson(response));
        return response;
    }

    /**
     * 设备到位反馈：以到位反馈为准推进先阀后机/逆序复位序列。
     */
    @Transactional
    public FeedbackResponse feedback(String requestId, Long sessionId,
                                     String deviceCode, String result) {
        if (sessionId == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "sessionId 不能为空");
        }
        if (!IN_POSITION.equals(result)) {
            throw ApiException.badRequest("INVALID_FEEDBACK_RESULT", "result 仅支持 IN_POSITION");
        }
        if (masterData.findDevice(deviceCode) == null) {
            throw ApiException.badRequest("DEVICE_NOT_FOUND", "设备不存在：" + deviceCode);
        }
        String fingerprint = Fingerprints.of(requestId, RequestType.FEEDBACK.name(),
                String.valueOf(sessionId), deviceCode, result);
        IdemRequestRow existing = idempotency.find(requestId);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEM_KEY_MISMATCH",
                        "requestId=" + requestId + " 已存在但业务参数不一致（409），不产生新指令");
            }
            FeedbackResponse first = readJson(existing.resultJson(), FeedbackResponse.class);
            return new FeedbackResponse(true, first.accepted(), first.late(), first.sessionId(),
                    first.commandId(), first.status(), first.note());
        }
        long now = clock.nowMillis();
        if (!idempotency.insert(requestId, RequestType.FEEDBACK.name(), null, fingerprint,
                "SUCCESS", null, null, now)) {
            IdemRequestRow raced = idempotency.find(requestId);
            if (!raced.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("IDEM_KEY_MISMATCH",
                        "requestId=" + requestId + " 已存在但业务参数不一致（409），不产生新指令");
            }
            FeedbackResponse first = readJson(raced.resultJson(), FeedbackResponse.class);
            return new FeedbackResponse(true, first.accepted(), first.late(), first.sessionId(),
                    first.commandId(), first.status(), first.note());
        }

        ModeSessionRow session = sessions.findById(sessionId);
        if (session == null) {
            FeedbackResponse resp = persistFeedback(requestId, "FEEDBACK_SESSION_NOT_FOUND",
                    null, null, null, deviceCode, false, now, false);
            idempotency.updateResult(requestId, "CONFLICT", resp.note(), writeJson(resp));
            return resp;
        }

        // 归档为终态：迟到反馈只记迟到事件，不改变归档结论。
        if (session.getStatus() == ModeStatus.ARCHIVED) {
            FeedbackResponse resp = persistFeedback(requestId, "LATE_FEEDBACK_IGNORED",
                    session.getId(), null, session.getZoneCode(), deviceCode, false, now, true);
            idempotency.updateResult(requestId, "SUCCESS", resp.note(), writeJson(resp));
            return resp;
        }

        DeviceCommandRow command = commands.findActiveByDevice(sessionId, deviceCode);
        if (command == null || command.getStatus() != CommandStatus.SENT) {
            FeedbackResponse resp = persistFeedback(requestId, "NO_PENDING_COMMAND",
                    session.getId(), command == null ? null : command.getId(),
                    session.getZoneCode(), deviceCode, false, now, false);
            idempotency.updateResult(requestId, "SUCCESS", resp.note(), writeJson(resp));
            return resp;
        }

        commands.markAcked(command.getId(), now);
        events.append("FEEDBACK_RECEIVED", session.getId(), session.getZoneCode(), requestId,
                deviceCode + " 到位（" + command.getAction() + "，" + command.getPhase()
                        + "，第 " + command.getAttempt() + " 次下发）", now);
        feedbacks.insert(requestId, session.getId(), command.getId(), session.getZoneCode(),
                deviceCode, result, false, now);

        // 到位后尝试推进序列：风阀齐了启风机 / 风机停了关风阀。
        String activeRequestId = session.getActivateRequestId();
        if (session.getStatus() == ModeStatus.ACTIVE) {
            dispatchEligible(sessionId, PHASE_ACTIVATE, activeRequestId, now);
        }
        ModeResponse resetResponse = null;
        if (session.getStatus() == ModeStatus.RESETTING) {
            dispatchEligible(sessionId, PHASE_RESET,
                    commands.findBySessionAndPhase(sessionId, PHASE_RESET).stream()
                            .findFirst().map(DeviceCommandRow::getRequestId).orElse(activeRequestId),
                    now);
            resetResponse = checkResetComplete(sessionId, activeRequestId, now);
        }

        String note = "设备 " + deviceCode + " 到位已确认";
        if (resetResponse != null && OUTCOME_RESET.equals(resetResponse.outcome())) {
            note += "；复位全部到位，分区锁已释放";
        }
        FeedbackResponse response = new FeedbackResponse(false, true, false, sessionId,
                command.getId(), CommandStatus.ACKED.name(), note);
        idempotency.updateResult(requestId, "SUCCESS", null, writeJson(response));
        return response;
    }

    /**
     * 超时巡检：下发满 60 秒（恰满判超时）未到位则重发一次；第二次仍超时转设备故障并升级告警。
     * 故障设备不阻塞其他分区指令。可由调度器定时调用，也可在测试中显式调用。
     *
     * @return 本轮处理的指令数量（重发 + 故障）
     */
    @Transactional
    public int scanTimeouts() {
        long now = clock.nowMillis();
        List<DeviceCommandRow> timedOut = commands.findTimedOutSent(now);
        int processed = 0;
        for (DeviceCommandRow command : timedOut) {
            if (command.getAttempt() >= 2) {
                commands.markFault(command.getId(), now);
                events.append("DEVICE_FAULT", command.getSessionId(), command.getZoneCode(),
                        command.getRequestId(),
                        "设备 " + command.getDeviceCode() + " 两次下发均超时（"
                                + command.getAction() + "，" + command.getPhase() + "），判设备故障",
                        now);
                events.append("ALARM_ESCALATED", command.getSessionId(), command.getZoneCode(),
                        command.getRequestId(),
                        "设备故障告警升级：" + command.getDeviceCode(), now);
                sessions.markFault(command.getSessionId());
                log.warn("设备故障并升级告警：session={}, device={}",
                        command.getSessionId(), command.getDeviceCode());
            } else {
                long due = now + properties.feedbackTimeoutSeconds() * 1000L;
                commands.markSent(command.getId(), 2, now, due);
                events.append("COMMAND_RESENT", command.getSessionId(), command.getZoneCode(),
                        command.getRequestId(),
                        "设备 " + command.getDeviceCode() + " 到位反馈超时 60 秒，自动重发第 2 次",
                        now);
            }
            processed++;
        }
        return processed;
    }

    /** 查询会话视图（含全部指令）。 */
    @Transactional(readOnly = true)
    public SessionView getSession(long sessionId) {
        ModeSessionRow session = sessions.findById(sessionId);
        if (session == null) {
            throw ApiException.notFound("SESSION_NOT_FOUND", "会话不存在：" + sessionId);
        }
        return toSessionView(session);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 下发阶段内所有已满足前置条件的 PENDING 指令。
     * 顺序约束：seq 较小者全部到位（或 CONFLICT/ALIGNED 跳过）后才下发后续指令；
     * FAULT/PENDING/SENT 的前序设备会阻塞后继，保证顺序不可颠倒。
     */
    private void dispatchEligible(long sessionId, String phase, String requestId, long now) {
        List<DeviceCommandRow> rows = commands.findBySessionAndPhase(sessionId, phase);
        // 本地状态镜像：循环中下发后同步更新，避免用过期内存状态误判前置未完成。
        Map<Long, CommandStatus> effective = new java.util.HashMap<>();
        for (DeviceCommandRow row : rows) {
            effective.put(row.getId(), row.getStatus());
        }
        long due = now + properties.feedbackTimeoutSeconds() * 1000L;
        for (DeviceCommandRow row : rows) {
            if (effective.get(row.getId()) != CommandStatus.PENDING) {
                continue;
            }
            int wave = waveRank(phase, row.getDeviceType());
            // 波次门控：激活=全部风阀先并行下发，到位齐后才启风机；
            // 复位=全部风机先并行下发，停稳后才关风阀。同波次设备互不等待。
            // 同区 FAULT 设备未到位，不推进后续波次（顺序不可颠倒）；故障已升级告警，
            // 分区保持故障态由人工处置，其他分区指令不受影响。
            boolean blocked = rows.stream()
                    .anyMatch(c -> waveRank(phase, c.getDeviceType()) < wave
                            && effective.get(c.getId()) != CommandStatus.ACKED
                            && effective.get(c.getId()) != CommandStatus.CONFLICT
                            && effective.get(c.getId()) != CommandStatus.ALIGNED);
            if (blocked) {
                break;
            }
            commands.markSent(row.getId(), 1, now, due);
            effective.put(row.getId(), CommandStatus.SENT);
            events.append("COMMAND_SENT", sessionId, row.getZoneCode(), requestId,
                    "下发指令：" + row.getDeviceCode() + " " + row.getAction()
                            + "（" + phase + "，seq=" + row.getSeqNo() + "，到位截止 "
                            + properties.feedbackTimeoutSeconds() + " 秒）",
                    now);
        }
    }

    /**
     * 波次序号：激活阶段风阀波(0)先于风机波(1)——先阀后机；
     * 复位阶段风机波(0)先于风阀波(1)——先停风机后关风阀的逆序。
     */
    private int waveRank(String phase, DeviceType deviceType) {
        if (PHASE_ACTIVATE.equals(phase)) {
            return deviceType == DeviceType.DAMPER ? 0 : 1;
        }
        return deviceType == DeviceType.FAN ? 0 : 1;
    }

    /**
     * 复位阶段所有指令全部到位（ACKED；CONFLICT/ALIGNED 未产生复位指令）则复位完成：
     * 释放分区锁与共享风阀占用。仍有 PENDING/SENT/FAULT 则继续等待或保持故障态人工处置。
     */
    private ModeResponse checkResetComplete(long sessionId, String requestId, long now) {
        ModeSessionRow session = sessions.findById(sessionId);
        List<DeviceCommandRow> resetCommands =
                commands.findBySessionAndPhase(sessionId, PHASE_RESET);
        boolean unfinished = resetCommands.stream()
                .anyMatch(c -> c.getStatus() != CommandStatus.ACKED);
        if (!unfinished && session.getStatus() == ModeStatus.RESETTING) {
            sessions.markResetComplete(sessionId, now);
            sessions.releaseZoneLock(session.getZoneCode());
            sessions.releaseSharedDampers(sessionId);
            events.append("RESET_COMPLETE", sessionId, session.getZoneCode(), requestId,
                    "逆序复位全部到位（先停风机、后关风阀），分区可重新激活", now);
            return modeResponse(false, OUTCOME_RESET, null, sessions.findById(sessionId),
                    List.of("复位完成；归档前为 RESET_COMPLETE 状态"));
        }
        String outcome = session.getStatus() == ModeStatus.RESET_COMPLETE
                ? OUTCOME_RESET : OUTCOME_RESETTING;
        return modeResponse(false, outcome, null, sessions.findById(sessionId),
                session.getStatus() == ModeStatus.RESETTING
                        ? List.of("复位指令按逆序执行中，等待设备到位反馈") : List.of());
    }

    private FeedbackResponse persistFeedback(String requestId, String code, Long sessionId,
                                             Long commandId, String zoneCode, String deviceCode,
                                             boolean accepted, long now, boolean late) {
        feedbacks.insert(requestId, sessionId, commandId, zoneCode, deviceCode, IN_POSITION,
                late, now);
        String eventType = late ? "LATE_FEEDBACK" : "FEEDBACK_RECEIVED";
        events.append(eventType, sessionId, zoneCode, requestId,
                code + "：" + deviceCode, now);
        return new FeedbackResponse(false, accepted, late, sessionId, commandId,
                late ? "ARCHIVED" : null,
                switch (code) {
                    case "LATE_FEEDBACK_IGNORED" -> "模式已归档，迟到反馈只记事件，不改变归档结论";
                    case "NO_PENDING_COMMAND" -> "设备当前无待到位指令，反馈仅登记";
                    default -> "会话不存在，反馈仅登记";
                });
    }

    private void validateZone(String zoneCode) {
        if (zoneCode == null || zoneCode.isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "zoneCode 不能为空");
        }
        if (masterData.findZone(zoneCode) == null) {
            throw ApiException.badRequest("ZONE_NOT_FOUND", "防火分区不存在：" + zoneCode);
        }
    }

    /** 同键重放：指纹一致返回首次结果，同键异参 409 且不产生新指令。 */
    private ModeResponse handleIdemReplay(IdemRequestRow existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEM_KEY_MISMATCH",
                    "requestId=" + existing.requestId()
                            + " 已存在但业务参数不一致（409），不产生新指令");
        }
        if (existing.resultJson() == null) {
            throw ApiException.conflict("IDEM_RESULT_NOT_READY",
                    "相同 requestId 的首次请求仍在处理中，请稍后重放");
        }
        ModeResponse first = readJson(existing.resultJson(), ModeResponse.class);
        return new ModeResponse(true, first.outcome(), first.conflictReason(),
                first.session(), first.notes());
    }

    private ModeResponse modeResponse(boolean replay, String outcome, String conflictReason,
                                      ModeSessionRow session, List<String> notes) {
        SessionView view = session == null ? null : toSessionView(session);
        return new ModeResponse(replay, outcome, conflictReason, view,
                List.copyOf(notes));
    }

    private SessionView toSessionView(ModeSessionRow session) {
        List<CommandView> commandViews = commands.findBySession(session.getId()).stream()
                .map(c -> new CommandView(
                        c.getId(), c.getSeqNo(), c.getPhase(), c.getDeviceCode(),
                        c.getDeviceType().name(), c.getAction().name(), c.getAttempt(),
                        c.getStatus().name(), c.getSentAt(), c.getAckDueAt(),
                        c.getAckedAt(), c.getFaultAt()))
                .toList();
        return new SessionView(
                session.getId(), session.getZoneCode(), session.getActivateRequestId(),
                session.getSmokeDirection(), session.getTargetCondition(),
                session.getStatus().name(), session.isFault(), session.getActivatedAt(),
                session.getResetCompletedAt(), session.getArchivedAt(), commandViews);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
