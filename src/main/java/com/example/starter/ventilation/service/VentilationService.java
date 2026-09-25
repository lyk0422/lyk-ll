package com.example.starter.ventilation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.ventilation.ConflictException;
import com.example.starter.ventilation.NotFoundException;
import com.example.starter.ventilation.ServiceException;
import com.example.starter.ventilation.TimeService;
import com.example.starter.ventilation.dto.ActivateRequest;
import com.example.starter.ventilation.dto.ArchiveRequest;
import com.example.starter.ventilation.dto.CommandView;
import com.example.starter.ventilation.dto.EventView;
import com.example.starter.ventilation.dto.FeedbackRequest;
import com.example.starter.ventilation.dto.FeedbackResult;
import com.example.starter.ventilation.dto.ModeView;
import com.example.starter.ventilation.dto.ResetRequest;
import com.example.starter.ventilation.repo.CommandRow;
import com.example.starter.ventilation.repo.EventRow;
import com.example.starter.ventilation.repo.FireModeRow;
import com.example.starter.ventilation.repo.VentilationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 隧道火灾通风联动核心服务：激活排烟、到位反馈、超时重发与故障升级、
 * 逆序复位、归档终态，以及基于 requestId+业务指纹的幂等控制。
 *
 * <p>事务边界：每个公开业务操作在单个事务内执行；幂等记录在业务事务之外
 * 独立写入，使竞争失败（事务回滚）的首次结论同样可被同键重放。</p>
 */
@Service
public class VentilationService {

    private static final String PURGE = "PURGE";
    private static final String RESET = "RESET";

    private final VentilationRepository repo;
    private final TimeService timeService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final long feedbackTimeoutSeconds;

    public VentilationService(VentilationRepository repo,
                              TimeService timeService,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager txManager,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${app.ventilation.feedback-timeout-seconds:60}")
                              long feedbackTimeoutSeconds) {
        this.repo = repo;
        this.timeService = timeService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.feedbackTimeoutSeconds = feedbackTimeoutSeconds;
    }

    // ================= 激活 =================

    /** 激活火灾模式：同键同参返回首次结果，同键异参 409 且不产生新指令。 */
    public ModeView activate(ActivateRequest request) {
        String fp = fingerprint("ACTIVATE", request.requestId(),
                request.zoneCode(), request.smokeFlowDirection(), request.targetCondition());
        return withIdempotency(request.requestId(), "ACTIVATE", fp, ModeView.class,
                () -> doActivate(request, fp));
    }

    private ModeView doActivate(ActivateRequest request, String fp) {
        var now = timeService.now();
        if (!repo.zoneExists(request.zoneCode())) {
            throw new NotFoundException("防火分区不存在: " + request.zoneCode());
        }
        long modeId = repo.insertFireMode(request.requestId(), request.zoneCode(),
                request.smokeFlowDirection(), request.targetCondition(), fp, "ACTIVE", now);

        if (!repo.tryAcquireZoneLock(request.zoneCode(), modeId, now)) {
            // 同一分区同一时刻只允许一个激活中的火灾模式；回滚本事务，不产生任何指令。
            throw new ConflictException("分区 " + request.zoneCode()
                    + " 已有激活中的火灾模式，并发激活仅一个成功");
        }
        repo.insertEvent(modeId, request.zoneCode(), request.requestId(),
                "ZONE_LOCK_ACQUIRED", "获取分区激活锁", now);

        List<VentilationRepository.ZoneDeviceRow> plan =
                repo.listZoneDevices(request.zoneCode());
        List<String> conflicts = new ArrayList<>();
        for (var d : plan) {
            String uid = commandUid(modeId, PURGE, d.stepOrder(), d.deviceCode());
            repo.insertCommand(uid, modeId, request.zoneCode(), d.deviceCode(), d.deviceType(),
                    d.purgeTarget(), PURGE, d.stepOrder(), "PENDING", now);
        }
        // 共享风阀：以先激活分区的目标状态为准，后激活分区的冲突指令跳过并给出说明。
        for (var d : plan) {
            if (!repo.isSharedDevice(d.deviceCode())) {
                continue;
            }
            String uid = commandUid(modeId, PURGE, d.stepOrder(), d.deviceCode());
            long cmdId = repo.findCommandByUid(uid).orElseThrow().id();
            List<CommandRow> prior = repo.listEffectiveActiveCommands(
                    d.deviceCode(), PURGE, modeId);
            if (!prior.isEmpty()) {
                CommandRow winner = prior.get(0);
                if (!winner.targetState().equals(d.purgeTarget())) {
                    String reason = "共享风阀 " + d.deviceCode() + " 已由先激活分区模式 "
                            + "requestId=" + modeRequestId(winner.modeId())
                            + " 占用为目标状态 " + winner.targetState()
                            + "，本分区目标 " + d.purgeTarget() + " 与之冲突，遵从先激活分区";
                    repo.markCommandSkipped(cmdId, reason);
                    conflicts.add(reason);
                    repo.insertEvent(modeId, request.zoneCode(), request.requestId(),
                            "SHARED_DAMPER_CONFLICT", reason, now);
                }
            }
        }

        repo.insertEvent(modeId, request.zoneCode(), request.requestId(),
                "MODE_ACTIVATED", "火灾模式激活，生成先阀后机指令序列 " + plan.size()
                        + " 条；烟气流向假设=" + request.smokeFlowDirection()
                        + "，目标工况=" + request.targetCondition(), now);

        advanceCommands(modeId, PURGE, request.requestId(), now);
        if (!conflicts.isEmpty()) {
            repo.updateModeConflictDetail(modeId, String.join("; ", conflicts));
        }
        return loadModeView(modeId);
    }

    // ================= 复位 =================

    /** 火警解除：按逆序生成复位指令（先停风机、到位后关风阀）。 */
    public ModeView reset(ResetRequest request) {
        String fp = fingerprint("RESET", request.requestId(), request.modeRequestId());
        return withIdempotency(request.requestId(), "RESET", fp, ModeView.class,
                () -> doReset(request, fp));
    }

    private ModeView doReset(ResetRequest request, String fp) {
        FireModeRow mode = repo.findFireModeByRequestId(request.modeRequestId())
                .orElseThrow(() -> new NotFoundException(
                        "火灾模式不存在: " + request.modeRequestId()));
        switch (mode.status()) {
            case "ACTIVE" -> {
                // 合法：开始逆序复位
            }
            case "RESETTING", "RESET_COMPLETE" -> throw new ConflictException(
                    "火灾模式 " + request.modeRequestId() + " 已在复位流程中/已完成复位");
            case "ARCHIVED" -> throw new ConflictException(
                    "火灾模式 " + request.modeRequestId() + " 已归档，不可复位");
            default -> throw new IllegalStateException(mode.status());
        }
        boolean purgeWaiting = repo.listCommandsByMode(mode.id()).stream()
                .anyMatch(c -> PURGE.equals(c.phase()) && "ISSUED".equals(c.status()));
        if (purgeWaiting) {
            throw new ConflictException("分区 " + mode.zoneCode()
                    + " 排烟联动尚有设备指令等待到位反馈，不可复位");
        }
        var now = timeService.now();
        // 复位期间继续持有分区锁：复位完成前该分区不接受新的激活指令。
        repo.markResetStarted(mode.id(), now);
        repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                "RESET_STARTED", "火警解除，开始逆序复位（先停风机、到位后关风阀）", now);

        List<VentilationRepository.ZoneDeviceRow> plan =
                repo.listZoneDevices(mode.zoneCode());
        Map<String, CommandRow> purgeByDevice = new LinkedHashMap<>();
        for (CommandRow c : repo.listCommandsByMode(mode.id())) {
            if (PURGE.equals(c.phase())) {
                purgeByDevice.put(c.deviceCode(), c);
            }
        }
        List<String> conflicts = new ArrayList<>();
        for (var d : plan) {
            String uid = commandUid(mode.id(), RESET, d.stepOrder(), d.deviceCode());
            repo.insertCommand(uid, mode.id(), mode.zoneCode(), d.deviceCode(), d.deviceType(),
                    d.resetTarget(), RESET, d.stepOrder(), "PENDING", now);
            // 仅排烟阶段真正到位的设备需要逆序复位；冲突跳过/故障/联锁未启动的设备
            // 本模式未改变其状态，复位指令直接跳过。
            CommandRow purgeCmd = purgeByDevice.get(d.deviceCode());
            String purgeStatus = purgeCmd == null ? "" : purgeCmd.status();
            if (!"IN_POSITION".equals(purgeStatus)) {
                String reason = "排烟阶段设备 " + d.deviceCode() + " 状态为 " + purgeStatus
                        + "（未到位/未启动/冲突跳过），本模式未改变其状态，复位无需动作";
                repo.markCommandSkipped(repo.findCommandByUid(uid).orElseThrow().id(), reason);
            }
        }
        // 复位共享风阀时，若仍有其他激活中分区有效占用且目标不同，继续遵从先激活分区。
        for (var d : plan) {
            if (!repo.isSharedDevice(d.deviceCode())) {
                continue;
            }
            String uid = commandUid(mode.id(), RESET, d.stepOrder(), d.deviceCode());
            long cmdId = repo.findCommandByUid(uid).orElseThrow().id();
            List<CommandRow> prior = repo.listEffectiveActiveCommands(
                    d.deviceCode(), PURGE, mode.id());
            if (!prior.isEmpty()) {
                CommandRow winner = prior.get(0);
                if (!winner.targetState().equals(d.resetTarget())) {
                    String reason = "复位共享风阀 " + d.deviceCode() + " 时，先激活分区模式 "
                            + "requestId=" + modeRequestId(winner.modeId()) + " 仍要求 "
                            + winner.targetState() + "，本分区复位目标 " + d.resetTarget()
                            + " 冲突，遵从先激活分区，跳过本复位指令";
                    repo.markCommandSkipped(cmdId, reason);
                    conflicts.add(reason);
                    repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                            "SHARED_DAMPER_CONFLICT", reason, now);
                }
            }
        }
        if (!conflicts.isEmpty()) {
            String existed = mode.conflictDetail() == null ? "" : mode.conflictDetail() + "; ";
            repo.updateModeConflictDetail(mode.id(), existed + String.join("; ", conflicts));
        }
        advanceCommands(mode.id(), RESET, mode.requestId(), now);
        // 复位指令可能全部跳过（如故障分区），推进后同样需要完成判定。
        completePhaseIfDone(mode.id(), RESET, now);
        return loadModeView(mode.id());
    }

    // ================= 归档 =================

    /** 归档为终态；重复归档返回幂等成功，归档后迟到反馈不改变归档结论。 */
    public ModeView archive(ArchiveRequest request) {
        String fp = fingerprint("ARCHIVE", request.requestId(), request.modeRequestId());
        return withIdempotency(request.requestId(), "ARCHIVE", fp, ModeView.class,
                () -> doArchive(request));
    }

    private ModeView doArchive(ArchiveRequest request) {
        FireModeRow mode = repo.findFireModeByRequestId(request.modeRequestId())
                .orElseThrow(() -> new NotFoundException(
                        "火灾模式不存在: " + request.modeRequestId()));
        if ("ARCHIVED".equals(mode.status())) {
            // 模式终态：重复归档直接幂等成功，不新增状态迁移。
            return loadModeView(mode.id());
        }
        if (!"RESET_COMPLETE".equals(mode.status())) {
            throw new ConflictException("火灾模式 " + request.modeRequestId()
                    + " 尚未完成复位（当前 " + mode.status() + "），不能归档");
        }
        var now = timeService.now();
        repo.markArchived(mode.id(), now);
        repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                "MODE_ARCHIVED", "火灾模式归档，进入终态；此后迟到反馈仅记录不改变结论", now);
        return loadModeView(mode.id());
    }

    // ================= 设备反馈 =================

    /** 设备到位反馈；归档后迟到反馈只记迟到事件，不改变归档结论。 */
    public FeedbackResult feedback(FeedbackRequest request) {
        String fp = fingerprint("FEEDBACK", request.requestId(), request.commandUid(),
                request.deviceCode(), String.valueOf(request.inPosition()),
                request.observedState() == null ? "" : request.observedState());
        return withIdempotency(request.requestId(), "FEEDBACK", fp, FeedbackResult.class,
                () -> doFeedback(request));
    }

    private FeedbackResult doFeedback(FeedbackRequest request) {
        CommandRow cmd = repo.findCommandByUid(request.commandUid())
                .orElseThrow(() -> new NotFoundException(
                        "设备指令不存在: " + request.commandUid()));
        if (!cmd.deviceCode().equals(request.deviceCode())) {
            throw new ConflictException("反馈设备 " + request.deviceCode()
                    + " 与指令所属设备 " + cmd.deviceCode() + " 不一致");
        }
        FireModeRow mode = repo.findFireModeById(cmd.modeId()).orElseThrow();
        var now = timeService.now();

        if ("ARCHIVED".equals(mode.status())) {
            repo.insertFeedback(cmd.commandUid(), mode.id(), request.deviceCode(),
                    request.inPosition(), request.observedState(), true,
                    "归档后迟到反馈", now);
            repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                    "LATE_FEEDBACK", "归档终态后收到设备 " + request.deviceCode()
                            + " 迟到反馈(inPosition=" + request.inPosition()
                            + ")，仅记录，不改变归档结论", now);
            return new FeedbackResult(false, true, cmd.commandUid(), cmd.status(),
                    "模式已归档，迟到反馈仅记录为迟到事件");
        }

        repo.insertFeedback(cmd.commandUid(), mode.id(), request.deviceCode(),
                request.inPosition(), request.observedState(), false, null, now);

        if (request.inPosition()) {
            switch (cmd.status()) {
                case "ISSUED" -> {
                    repo.markCommandInPosition(cmd.id(), now);
                    repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                            "COMMAND_IN_POSITION", "设备 " + request.deviceCode()
                                    + " 到位确认（" + cmd.phase() + " 第" + cmd.stepOrder()
                                    + "步，目标=" + cmd.targetState() + "）", now);
                    afterProgress(mode, cmd.phase(), now);
                    return new FeedbackResult(true, false, cmd.commandUid(),
                            "IN_POSITION", "到位确认成功，已按预案尝试推进后续指令");
                }
                case "PENDING" -> {
                    repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                            "FEEDBACK_RECEIVED", "设备 " + request.deviceCode()
                                    + " 指令尚未下发，到位反馈仅记录", now);
                    return new FeedbackResult(false, false, cmd.commandUid(), "PENDING",
                            "指令尚未下发，反馈仅记录");
                }
                default -> {
                    repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                            "FEEDBACK_RECEIVED", "设备 " + request.deviceCode()
                                    + " 指令已为终态 " + cmd.status() + "，反馈仅记录", now);
                    return new FeedbackResult(false, false, cmd.commandUid(), cmd.status(),
                            "指令已处于终态 " + cmd.status() + "，反馈仅记录");
                }
            }
        }
        repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                "FEEDBACK_NOT_IN_POSITION", "设备 " + request.deviceCode()
                        + " 回报尚未到位，继续等待到位或超时重发", now);
        return new FeedbackResult(true, false, cmd.commandUid(), cmd.status(),
                "已受理：设备尚未到位，继续等待到位反馈或超时重发");
    }

    // ================= 超时扫描 =================

    /**
     * 扫描已下发指令：首发恰满 60 秒未到位则自动重发一次；重发后再恰满 60 秒
     * 仍未到位则转设备故障并升级告警。每条指令独立事务，单条故障不阻塞其他分区。
     *
     * @return 本轮发生重发或转故障的指令编码
     */
    public List<String> sweepTimeouts() {
        List<String> touched = new ArrayList<>();
        for (CommandRow snapshot : repo.listIssuedCommands()) {
            try {
                String uid = tx.execute(status -> processTimeout(snapshot.commandUid()));
                if (uid != null) {
                    touched.add(uid);
                }
            } catch (Exception ex) {
                // 单条处理失败不影响其他分区/其他指令的超时处理。
                try {
                    var now = timeService.now();
                    repo.insertEvent(snapshot.modeId(), snapshot.zoneCode(), null,
                            "SWEEP_ERROR", "超时扫描处理指令 " + snapshot.commandUid()
                                    + " 异常: " + ex.getMessage(), now);
                } catch (Exception ignored) {
                    // 记录失败也不中断扫描
                }
            }
        }
        return touched;
    }

    /** 必须运行在事务中；返回被处理的指令编码，未到超时点返回 null。 */
    private String processTimeout(String commandUid) {
        CommandRow cmd = repo.findCommandByUid(commandUid).orElse(null);
        if (cmd == null || !"ISSUED".equals(cmd.status()) || cmd.issuedAt() == null) {
            return null;
        }
        var now = timeService.now();
        var deadline = cmd.issuedAt().plusSeconds(feedbackTimeoutSeconds);
        // 恰满 60 秒即判超时。
        if (now.isBefore(deadline)) {
            return null;
        }
        FireModeRow mode = repo.findFireModeById(cmd.modeId()).orElseThrow();
        if (cmd.attempt() <= 1) {
            int updated = repo.resendCommandIfAttempt(cmd.id(), 1, 2, now);
            if (updated == 0) {
                return null; // 已被反馈或并发扫描抢先处理
            }
            repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                    "COMMAND_RESEND", "设备 " + cmd.deviceCode() + " 首发满 "
                            + feedbackTimeoutSeconds + " 秒未收到到位反馈，自动重发一次",
                    now);
            return cmd.commandUid();
        }
        String reason = "重发后再满 " + feedbackTimeoutSeconds
                + " 秒仍未收到到位反馈，判定设备故障";
        int failed = repo.failCommandIfAttempt(cmd.id(), 2, now, reason);
        if (failed == 0) {
            return null;
        }
        repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                "DEVICE_FAULT", "设备 " + cmd.deviceCode() + "（" + cmd.deviceType()
                        + "）故障：" + reason, now);
        repo.insertEvent(mode.id(), mode.zoneCode(), mode.requestId(),
                "ALARM_ESCALATED", "告警升级：分区 " + cmd.zoneCode() + " 设备 "
                        + cmd.deviceCode() + " 两次下发均超时，转设备故障并升级告警；"
                        + "故障设备不阻塞其他分区指令", now);
        return cmd.commandUid();
    }

    // ================= 查询 =================

    /** 查询模式全量视图（含按序指令）。 */
    public ModeView getMode(String requestId) {
        FireModeRow mode = repo.findFireModeByRequestId(requestId)
                .orElseThrow(() -> new NotFoundException("火灾模式不存在: " + requestId));
        return loadModeView(mode.id());
    }

    /** 有序事件查询：requestId 为空时返回全量。 */
    public List<EventView> listEvents(String requestId) {
        List<EventRow> rows = (requestId == null || requestId.isBlank())
                ? repo.listAllEvents()
                : repo.listEventsByRequestId(requestId);
        return rows.stream().map(r -> new EventView(
                r.id(), r.modeId(), r.zoneCode(), r.requestId(),
                r.eventType(), r.detail(), r.eventTime())).toList();
    }

    // ================= 内部：指令推进 =================

    /**
     * 按预案推进一个阶段的指令。PURGE 按 step 升序（先阀后机），
     * RESET 按 step 降序（先风机后风阀）；前一步序全部有效指令到位后才下发下一步序，
     * 顺序不可颠倒；SKIPPED 不参与等待，FAILED 触发同分区联锁（后续不再下发）。
     */
    private void advanceCommands(long modeId, String phase, String requestId,
                                 java.time.LocalDateTime now) {
        List<CommandRow> phaseCmds = repo.listCommandsByMode(modeId).stream()
                .filter(c -> c.phase().equals(phase)).toList();
        Comparator<Integer> stepOrder = PURGE.equals(phase)
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Map<Integer, List<CommandRow>> byStep = new TreeMap<>(stepOrder);
        for (CommandRow c : phaseCmds) {
            byStep.computeIfAbsent(c.stepOrder(), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<Integer, List<CommandRow>> e : byStep.entrySet()) {
            List<CommandRow> effective = e.getValue().stream()
                    .filter(c -> !"SKIPPED".equals(c.status())).toList();
            if (effective.isEmpty()) {
                continue; // 该步序全部冲突跳过
            }
            if (effective.stream().anyMatch(c -> "ISSUED".equals(c.status()))) {
                return; // 本步序仍在等待到位
            }
            if (effective.stream().anyMatch(c -> "FAILED".equals(c.status()))) {
                return; // 同分区安全联锁：前序故障，后续不下发
            }
            if (effective.stream().allMatch(c -> "IN_POSITION".equals(c.status()))) {
                continue; // 本步序已到位，进入下一步序
            }
            List<CommandRow> pending = effective.stream()
                    .filter(c -> "PENDING".equals(c.status())).toList();
            if (!pending.isEmpty()) {
                for (CommandRow c : pending) {
                    repo.markCommandIssued(c.id(), 1, now);
                    repo.insertEvent(modeId, c.zoneCode(), requestId,
                            "COMMAND_ISSUED", "下发设备指令：" + c.deviceCode() + "（"
                                    + c.deviceType() + "）目标=" + c.targetState()
                                    + "，" + phaseText(phase) + "第" + c.stepOrder() + "步",
                            now);
                }
                return; // 等待本步序到位
            }
        }
    }

    /** 到位反馈后推进，并在阶段首次全部终态时记录完成事件/释放分区锁。 */
    private void afterProgress(FireModeRow mode, String phase, java.time.LocalDateTime now) {
        advanceCommands(mode.id(), phase, mode.requestId(), now);
        completePhaseIfDone(mode.id(), phase, now);
    }

    /**
     * 阶段完成判定：该阶段不存在 PENDING/ISSUED 指令且此前未记录完成事件时，
     * 落完成事件；复位阶段额外置 RESET_COMPLETE 并释放分区激活锁。
     */
    private void completePhaseIfDone(long modeId, String phase, java.time.LocalDateTime now) {
        FireModeRow mode = repo.findFireModeById(modeId).orElseThrow();
        List<CommandRow> phaseCmds = repo.listCommandsByMode(modeId).stream()
                .filter(c -> c.phase().equals(phase)).toList();
        boolean stillOpen = phaseCmds.stream()
                .anyMatch(c -> "PENDING".equals(c.status()) || "ISSUED".equals(c.status()));
        if (stillOpen || phaseCmds.isEmpty()) {
            return;
        }
        String completeEvent = PURGE.equals(phase)
                ? "PURGE_SEQUENCE_COMPLETE" : "RESET_COMPLETED";
        boolean alreadyCompleted = repo.listEventsByMode(modeId).stream()
                .anyMatch(e -> completeEvent.equals(e.eventType()));
        if (alreadyCompleted) {
            return;
        }
        if (PURGE.equals(phase)) {
            repo.insertEvent(modeId, mode.zoneCode(), mode.requestId(),
                    completeEvent, "先阀后机排烟联动序列已全部到位/终态", now);
        } else {
            repo.markResetCompleted(modeId, now);
            // 复位完成，释放分区锁：此后该分区可接受新的激活指令。
            repo.releaseZoneLock(mode.zoneCode());
            repo.insertEvent(modeId, mode.zoneCode(), mode.requestId(),
                    completeEvent, "逆序复位全部完成（先停风机、到位后关风阀），"
                            + "分区激活锁已释放", now);
        }
    }

    private String phaseText(String phase) {
        return PURGE.equals(phase) ? "排烟" : "复位";
    }

    // ================= 内部：视图与工具 =================

    private ModeView loadModeView(long modeId) {
        FireModeRow m = repo.findFireModeById(modeId).orElseThrow();
        List<CommandRow> commands = repo.listCommandsByMode(modeId).stream()
                .sorted(Comparator
                        .comparing((CommandRow c) -> PURGE.equals(c.phase()) ? 0 : 1)
                        .thenComparing(c -> PURGE.equals(c.phase())
                                ? c.stepOrder() : -c.stepOrder())
                        .thenComparing(CommandRow::id))
                .toList();
        List<CommandView> views = commands.stream().map(c -> new CommandView(
                c.id(), c.commandUid(), c.deviceCode(), c.deviceType(), c.targetState(),
                c.phase(), c.stepOrder(), c.attempt(), c.status(), c.blockedReason(),
                c.issuedAt(), c.ackAt(), c.failedAt(), c.createdAt())).toList();
        return new ModeView(m.id(), m.requestId(), m.zoneCode(), m.smokeFlowDirection(),
                m.targetCondition(), m.status(), m.conflictDetail(), m.createdAt(),
                m.resetStartedAt(), m.resetCompletedAt(), m.archivedAt(), views);
    }

    private String modeRequestId(long modeId) {
        return repo.findFireModeById(modeId).map(FireModeRow::requestId).orElse("?");
    }

    private static String commandUid(long modeId, String phase, int step, String deviceCode) {
        return "C" + modeId + "-" + (PURGE.equals(phase) ? "P" : "R") + step + "-" + deviceCode;
    }

    private static String fingerprint(String kind, String requestId, String... fields) {
        String raw = kind + "|" + requestId + "|" + String.join("|", fields);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 幂等模板：同 (requestId, kind) 同指纹返回首次结果；同键异参 409。
     * 业务事务回滚后幂等记录仍独立落库，保证竞争失败的首次结论也可重放。
     */
    private <T> T withIdempotency(String requestId, String kind, String fingerprint,
                                  Class<T> bodyType, SupplierTx<T> action) {
        var existing = repo.findIdem(requestId, kind);
        if (existing.isPresent()) {
            IdemRowView row = new IdemRowView(existing.get());
            if (!row.fingerprint.equals(fingerprint)) {
                throw new ConflictException("requestId=" + requestId
                        + " 已存在但业务参数与首次请求不一致（同键异参），拒绝执行");
            }
            return row.replay(bodyType);
        }
        T result;
        try {
            result = tx.execute(status -> action.get());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("requestId=" + requestId + " 的相同请求正在并发处理");
        } catch (ServiceException e) {
            // 失败结论仅在无首写时登记，不覆盖并发成功者稍后写入的 200 结论。
            rememberFailure(requestId, kind, fingerprint, e.getStatus().value(),
                    errorJson(e.getMessage()));
            throw e;
        }
        rememberSuccess(requestId, kind, fingerprint, toJson(result));
        return result;
    }

    private void rememberSuccess(String requestId, String kind, String fingerprint,
                                 String responseJson) {
        boolean firstWrite = repo.insertIdemIfAbsent(requestId, kind, fingerprint,
                200, responseJson, timeService.now());
        if (!firstWrite) {
            // 并发失败者先登记了 4xx；业务真相是 200，对齐为首次成功结论。
            repo.updateIdemOutcome(requestId, kind, fingerprint, 200, responseJson);
        }
    }

    private void rememberFailure(String requestId, String kind, String fingerprint,
                                 int httpStatus, String responseJson) {
        repo.insertIdemIfAbsent(requestId, kind, fingerprint, httpStatus, responseJson,
                timeService.now());
    }

    private String errorJson(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        return toJson(body);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化响应失败", e);
        }
    }

    @FunctionalInterface
    private interface SupplierTx<T> {
        T get();
    }

    /** 幂等行的重放视图：200 反序列化为原类型，4xx 重建业务异常。 */
    private final class IdemRowView {
        private final String fingerprint;
        private final int httpStatus;
        private final String responseJson;

        private IdemRowView(com.example.starter.ventilation.repo.IdemRow row) {
            this.fingerprint = row.fingerprint();
            this.httpStatus = row.httpStatus();
            this.responseJson = row.responseJson();
        }

        private <T> T replay(Class<T> bodyType) {
            if (httpStatus == 200) {
                try {
                    return objectMapper.readValue(responseJson, bodyType);
                } catch (Exception e) {
                    throw new IllegalStateException("重放首次响应失败", e);
                }
            }
            String message;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(
                        responseJson, Map.class);
                message = String.valueOf(body.getOrDefault("message", "首次请求返回冲突"));
            } catch (Exception e) {
                message = "首次请求返回 HTTP " + httpStatus;
            }
            HttpStatus status = HttpStatus.resolve(httpStatus);
            throw new ServiceException(status == null ? HttpStatus.CONFLICT : status, message);
        }
    }
}
