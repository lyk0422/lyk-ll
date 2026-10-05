package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.FeedResponse;
import com.example.starter.api.dto.StageConfirmRequest;
import com.example.starter.api.dto.StageConfirmResponse;
import com.example.starter.api.dto.WarmUpTicketCreateRequest;
import com.example.starter.api.dto.WarmUpTicketResponse;
import com.example.starter.config.CementProperties;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.RejectCategory;
import com.example.starter.domain.WarmUpStage;
import com.example.starter.domain.WarmUpStageType;
import com.example.starter.domain.WarmUpTicket;
import com.example.starter.domain.WarmUpTicketStatus;
import com.example.starter.repo.FeedRecordRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.KilnLockRepository;
import com.example.starter.repo.WarmUpStageRepository;
import com.example.starter.repo.WarmUpTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 窑冷修后升温操作票服务。
 *
 * <p>升温票按合成曲线分阶段执行：每段设目标温度与最长用时，恰满最长用时未达目标自动转入保温段；
 * 阶段确认须两名不同操作人复核，单人确认不生效；曲线未走完禁止投料，投料请求与曲线状态在同一事务
 * 校验；保温段恰满 4 小时未结束升级告警。所有边界均为“恰等于触发/达标”。</p>
 */
@Service
public class WarmUpService {

    private static final String TYPE_TICKET_CREATE = "WARMUP_TICKET_CREATE";
    private static final String TYPE_STAGE_CONFIRM = "WARMUP_STAGE_CONFIRM";
    private static final String TYPE_FEED = "FEED_REQUEST";

    private final WarmUpTicketRepository ticketRepository;
    private final WarmUpStageRepository stageRepository;
    private final FeedRecordRepository feedRecordRepository;
    private final KilnLockRepository kilnLockRepository;
    private final IdempotentRepository idempotentRepository;
    private final CementProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public WarmUpService(WarmUpTicketRepository ticketRepository,
                         WarmUpStageRepository stageRepository,
                         FeedRecordRepository feedRecordRepository,
                         KilnLockRepository kilnLockRepository,
                         IdempotentRepository idempotentRepository,
                         CementProperties properties,
                         Clock clock,
                         ObjectMapper objectMapper) {
        this.ticketRepository = ticketRepository;
        this.stageRepository = stageRepository;
        this.feedRecordRepository = feedRecordRepository;
        this.kilnLockRepository = kilnLockRepository;
        this.idempotentRepository = idempotentRepository;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------
    // 开票
    // ---------------------------------------------------------------------

    /**
     * 开具升温操作票：同窑同时刻只允许一张执行中/保温中升温票（窑升温槽唯一约束兜底），
     * 首个升温段与票在同一事务写入。以 requestId 幂等。
     */
    @Transactional
    public WarmUpTicketResponse createTicket(WarmUpTicketCreateRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            return replay(existing, fingerprint, TYPE_TICKET_CREATE, WarmUpTicketResponse.class);
        }

        // 同窑开票串行化，保证“同窑同时刻唯一受理”
        kilnLockRepository.lockKiln(request.kilnId());
        var committed = idempotentRepository.find(request.requestId());
        if (committed != null) {
            return replay(committed, fingerprint, TYPE_TICKET_CREATE, WarmUpTicketResponse.class);
        }

        List<CementProperties.CurveStage> curve = curveStages();
        LocalDateTime now = LocalDateTime.now(clock);
        String curveVersion = properties.getWarmUp().getCurveVersion();
        WarmUpTicket ticket = new WarmUpTicket(null, request.requestId(), generateTicketNo(now),
                request.kilnId(), curveVersion, WarmUpTicketStatus.EXECUTING, 1, null, now, null);
        long ticketId = ticketRepository.insert(ticket);
        if (!ticketRepository.tryAcquireKiln(request.kilnId(), ticketId, now)) {
            throw BusinessException.conflict(RejectCategory.WARMUP_TICKET_ACTIVE,
                    "窑 " + request.kilnId() + " 已存在执行中/保温中升温票，同窑同时刻只允许一张");
        }
        CementProperties.CurveStage first = curve.get(0);
        stageRepository.insertStage(new WarmUpStage(null, ticketId, 1, WarmUpStageType.RAMP,
                first.getStageNo(), curveVersion, first.getTargetTemp(), first.getMaxDurationMinutes(),
                now, null, null, null));

        WarmUpTicketResponse response = toResponse(ticketRepository.findById(ticketId));
        idempotentRepository.trySave(request.requestId(), TYPE_TICKET_CREATE, fingerprint, 200,
                writeJson(response), ticketId);
        return response;
    }

    // ---------------------------------------------------------------------
    // 阶段确认（双人复核）
    // ---------------------------------------------------------------------

    /**
     * 阶段确认：同阶段须两名不同操作人复核，第一人登记后阶段不推进；双人齐全后按
     * “达标推进 / 恰满最长用时未达目标转保温 / 否则拒绝”判定，阶段推进与曲线版本记录原子完成。
     */
    @Transactional
    public StageConfirmResponse confirmStage(String ticketNo, StageConfirmRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            return replay(existing, fingerprint, TYPE_STAGE_CONFIRM, StageConfirmResponse.class);
        }

        WarmUpTicket ticket = requireTicket(ticketNo);
        // 票级行锁：串行化同票并发确认，保证双人复核计数与阶段推进原子
        ticket = ticketRepository.lockById(ticket.id());
        if (ticket.status() == WarmUpTicketStatus.COMPLETED) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "升温票 " + ticketNo + " 曲线已走完，无需再确认阶段");
        }
        WarmUpStage stage = stageRepository.findCurrentStage(ticket.id());
        LocalDateTime now = LocalDateTime.now(clock);

        if (!stageRepository.insertConfirm(ticket.id(), stage.stageNo(), request.operator(), now)) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "操作人 " + request.operator() + " 已确认过阶段 " + stage.stageNo()
                            + "，单人确认不生效，须另一名操作人复核");
        }
        List<String> operators = stageRepository.findConfirmOperators(ticket.id(), stage.stageNo());
        if (operators.size() < 2) {
            StageConfirmResponse pending = new StageConfirmResponse(ticketNo, stage.stageNo(),
                    stage.stageType().name(), "PENDING_SECOND", operators, ticket.status().name());
            idempotentRepository.trySave(request.requestId(), TYPE_STAGE_CONFIRM, fingerprint, 200,
                    writeJson(pending), ticket.id());
            return pending;
        }

        String confirmStatus;
        if (stage.stageType() == WarmUpStageType.RAMP) {
            boolean tempReached = request.currentTemp().compareTo(stage.targetTemp()) >= 0;
            // 恰满最长用时即视为到期（started_at + maxDuration <= now）
            boolean durationUp = !stage.startedAt().plusMinutes(stage.maxDurationMinutes()).isAfter(now);
            if (tempReached) {
                confirmStatus = advanceToNext(ticket, stage, operators, now);
            } else if (durationUp) {
                // 恰满最长用时未达目标：自动转入保温段（承接当前升温段目标）
                stageRepository.markConfirmed(stage.id(), now, operators.get(0), operators.get(1));
                stageRepository.insertStage(new WarmUpStage(null, ticket.id(), stage.stageNo() + 1,
                        WarmUpStageType.HOLD, stage.curveStageNo(), ticket.curveVersion(),
                        stage.targetTemp(), null, now, null, null, null));
                updateProgress(ticket, WarmUpTicketStatus.HOLDING, stage.stageNo() + 1, now, null);
                confirmStatus = "HOLD_ENTERED";
            } else {
                throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                        "阶段 " + stage.stageNo() + " 实测温度 " + request.currentTemp()
                                + "℃ 未达目标 " + stage.targetTemp() + "℃ 且未满最长用时，不能推进");
            }
        } else {
            // 保温段：温度达到承接升温段目标方可结束保温
            if (request.currentTemp().compareTo(stage.targetTemp()) < 0) {
                throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                        "保温段实测温度 " + request.currentTemp() + "℃ 未达目标 "
                                + stage.targetTemp() + "℃，继续保温");
            }
            confirmStatus = advanceToNext(ticket, stage, operators, now);
        }

        WarmUpTicket updated = ticketRepository.findById(ticket.id());
        StageConfirmResponse response = new StageConfirmResponse(ticketNo, stage.stageNo(),
                stage.stageType().name(), confirmStatus, operators, updated.status().name());
        idempotentRepository.trySave(request.requestId(), TYPE_STAGE_CONFIRM, fingerprint, 200,
                writeJson(response), ticket.id());
        return response;
    }

    /**
     * 双人复核齐全且达标：完成当前阶段并推进。末段完成则票置 COMPLETED 并释放窑升温槽；
     * 否则同事务写入下一升温段（含曲线版本）并推进票阶段序号。
     */
    private String advanceToNext(WarmUpTicket ticket, WarmUpStage stage, List<String> operators,
                                 LocalDateTime now) {
        stageRepository.markConfirmed(stage.id(), now, operators.get(0), operators.get(1));
        List<CementProperties.CurveStage> curve = curveStages();
        int lastCurveStageNo = curve.get(curve.size() - 1).getStageNo();
        if (stage.curveStageNo() >= lastCurveStageNo) {
            updateProgress(ticket, WarmUpTicketStatus.COMPLETED, stage.stageNo(), null, now);
            ticketRepository.releaseKiln(ticket.kilnId());
            return "COMPLETED";
        }
        CementProperties.CurveStage next = curve.stream()
                .filter(s -> s.getStageNo() == stage.curveStageNo() + 1)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "合成曲线缺少阶段 " + (stage.curveStageNo() + 1)));
        stageRepository.insertStage(new WarmUpStage(null, ticket.id(), stage.stageNo() + 1,
                WarmUpStageType.RAMP, next.getStageNo(), ticket.curveVersion(), next.getTargetTemp(),
                next.getMaxDurationMinutes(), now, null, null, null));
        updateProgress(ticket, WarmUpTicketStatus.EXECUTING, stage.stageNo() + 1, null, null);
        return "ADVANCED";
    }

    private void updateProgress(WarmUpTicket ticket, WarmUpTicketStatus target, int stageNo,
                                LocalDateTime holdStartedAt, LocalDateTime completedAt) {
        int updated = ticketRepository.updateProgress(ticket.id(), ticket.status(), target, stageNo,
                holdStartedAt, completedAt);
        if (updated == 0) {
            throw BusinessException.conflict(RejectCategory.ILLEGAL_STATE,
                    "升温票 " + ticket.ticketNo() + " 状态已变化，阶段推进失败");
        }
    }

    // ---------------------------------------------------------------------
    // 投料
    // ---------------------------------------------------------------------

    /** 恢复投料：与曲线状态在同一事务校验，曲线未走完禁止投料。以 requestId 幂等。 */
    @Transactional
    public FeedResponse feed(FeedRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            return replay(existing, fingerprint, TYPE_FEED, FeedResponse.class);
        }

        // 投料校验与落库同事务：先对窑加行锁，再读曲线状态
        kilnLockRepository.lockKiln(request.kilnId());
        var committed = idempotentRepository.find(request.requestId());
        if (committed != null) {
            return replay(committed, fingerprint, TYPE_FEED, FeedResponse.class);
        }
        WarmUpTicket ticket = ticketRepository.findLatestByKiln(request.kilnId());
        if (ticket == null || ticket.status() != WarmUpTicketStatus.COMPLETED) {
            throw BusinessException.unprocessable(RejectCategory.CURVE_NOT_FINISHED,
                    "窑 " + request.kilnId() + " 升温曲线未走完，禁止投料");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long feedId = feedRecordRepository.insert(request.requestId(), request.kilnId(),
                ticket.id(), now);
        FeedResponse response = new FeedResponse(feedId, request.requestId(), request.kilnId(),
                ticket.ticketNo(), now);
        idempotentRepository.trySave(request.requestId(), TYPE_FEED, fingerprint, 200,
                writeJson(response), ticket.id());
        return response;
    }

    // ---------------------------------------------------------------------
    // 保温超时告警
    // ---------------------------------------------------------------------

    /** 扫描保温中升温票：进入保温恰满 4 小时（hold_started_at + 4h &lt;= now）未结束即升级告警。 */
    @Transactional
    public int scanHoldTimeouts() {
        LocalDateTime deadline = LocalDateTime.now(clock)
                .minusHours(properties.getWarmUp().getHoldTimeoutHours());
        int raised = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (WarmUpTicket ticket : ticketRepository.findHoldingOverdue(deadline)) {
            raised += stageRepository.insertAlarmIfAbsent(ticket.id(), ticket.currentStageNo(),
                    "HOLD_TIMEOUT", now);
        }
        return raised;
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public WarmUpTicketResponse status(String ticketNo) {
        return toResponse(requireTicket(ticketNo));
    }

    // ---------------------------------------------------------------------
    // 装配辅助
    // ---------------------------------------------------------------------

    private WarmUpTicket requireTicket(String ticketNo) {
        WarmUpTicket ticket = ticketRepository.findByTicketNo(ticketNo);
        if (ticket == null) {
            throw BusinessException.notFound("升温票 " + ticketNo + " 不存在");
        }
        return ticket;
    }

    private List<CementProperties.CurveStage> curveStages() {
        List<CementProperties.CurveStage> stages = properties.getWarmUp().getStages().stream()
                .sorted(Comparator.comparingInt(CementProperties.CurveStage::getStageNo))
                .toList();
        if (stages.isEmpty()) {
            throw new IllegalStateException("合成升温曲线未配置阶段");
        }
        return stages;
    }

    private WarmUpTicketResponse toResponse(WarmUpTicket ticket) {
        WarmUpStage stage = stageRepository.findCurrentStage(ticket.id());
        List<String> operators = stageRepository.findConfirmOperators(ticket.id(),
                ticket.currentStageNo());
        return new WarmUpTicketResponse(
                ticket.id(), ticket.ticketNo(), ticket.kilnId(), ticket.curveVersion(),
                ticket.status().name(), ticket.currentStageNo(),
                stage == null ? null : stage.stageType().name(),
                stage == null ? 0 : stage.curveStageNo(),
                stage == null ? null : stage.targetTemp(),
                stage == null ? null : stage.maxDurationMinutes(),
                stage == null ? null : stage.startedAt(),
                ticket.holdStartedAt(), operators, ticket.createdAt(), ticket.completedAt());
    }

    private String generateTicketNo(LocalDateTime now) {
        return "WU" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                .format(now) + String.format("%03d", (int) (Math.random() * 1000));
    }

    private <T> T replay(IdempotentRepository.IdempotentRecord existing, String fingerprint,
                         String expectedType, Class<T> type) {
        if (!existing.requestType().equals(expectedType)) {
            throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                    "requestId=" + existing.requestId() + " 已用于其他类型操作，拒绝受理");
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                    "requestId=" + existing.requestId() + " 已用于不同业务参数，拒绝受理");
        }
        return readJson(existing.responseBody(), type);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }
}
