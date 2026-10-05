package com.example.starter.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.FeedResponse;
import com.example.starter.api.dto.WarmupConfirmRequest;
import com.example.starter.api.dto.WarmupStageResponse;
import com.example.starter.api.dto.WarmupStageSpec;
import com.example.starter.api.dto.WarmupTicketCreateRequest;
import com.example.starter.api.dto.WarmupTicketResponse;
import com.example.starter.config.ApplicationClock;
import com.example.starter.config.CementProperties;
import com.example.starter.domain.BusinessException;
import com.example.starter.domain.RejectCategory;
import com.example.starter.domain.WarmupStage;
import com.example.starter.domain.WarmupStageStatus;
import com.example.starter.domain.WarmupStageType;
import com.example.starter.domain.WarmupTicket;
import com.example.starter.domain.WarmupTicketStatus;
import com.example.starter.repo.FeedRepository;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.repo.KilnLockRepository;
import com.example.starter.repo.WarmupStageRepository;
import com.example.starter.repo.WarmupTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 窑冷修后升温操作票服务：按合成曲线分阶段执行，阶段确认双人复核，
 * 恰满最长用时未达目标自动转保温段，保温段恰满 4 小时升级告警；
 * 曲线未走完禁止投料（投料请求与曲线状态在同一事务内校验）。
 *
 * <p>所有边界均为“恰满即触发”：升温段 entered_at+最长用时 &lt;= now 转保温，
 * 保温段 holding_since+4h &lt;= now 升级告警。</p>
 */
@Service
public class WarmupService {

    private final WarmupTicketRepository ticketRepository;
    private final WarmupStageRepository stageRepository;
    private final FeedRepository feedRepository;
    private final IdempotentRepository idempotentRepository;
    private final KilnLockRepository kilnLockRepository;
    private final CementProperties properties;
    private final ApplicationClock clock;
    private final ObjectMapper objectMapper;

    public WarmupService(WarmupTicketRepository ticketRepository,
                         WarmupStageRepository stageRepository,
                         FeedRepository feedRepository,
                         IdempotentRepository idempotentRepository,
                         KilnLockRepository kilnLockRepository,
                         CementProperties properties,
                         java.time.Clock clock,
                         ObjectMapper objectMapper) {
        this.ticketRepository = ticketRepository;
        this.stageRepository = stageRepository;
        this.feedRepository = feedRepository;
        this.idempotentRepository = idempotentRepository;
        this.kilnLockRepository = kilnLockRepository;
        this.properties = properties;
        this.clock = (ApplicationClock) clock;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------
    // 升温票创建
    // ---------------------------------------------------------------------

    /**
     * 创建升温操作票并进入第一阶段。以 requestId 幂等：同键同参返回首次结果，同键异参 409。
     * 同一窑同时刻只允许一张执行中升温票（占用槽唯一约束并发受理）。
     */
    @Transactional
    public WarmupTicketResponse create(WarmupTicketCreateRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同业务参数的升温票创建，拒绝重复受理");
            }
            return readJson(existing.responseBody(), WarmupTicketResponse.class);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 同窑创建串行化，保证“同窑同时刻只允许一张执行中升温票”
        kilnLockRepository.lockKiln(request.kilnId());
        var committed = idempotentRepository.find(request.requestId());
        if (committed != null) {
            if (!committed.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同业务参数的升温票创建，拒绝重复受理");
            }
            return readJson(committed.responseBody(), WarmupTicketResponse.class);
        }

        String ticketNo = generateTicketNo(now);
        WarmupTicket ticket = new WarmupTicket(null, request.requestId(), ticketNo,
                request.kilnId(), request.curveVersion(), WarmupTicketStatus.EXECUTING,
                1, now, null);
        long ticketId = ticketRepository.insert(ticket);
        if (!ticketRepository.tryAcquireKiln(request.kilnId(), ticketId, now)) {
            throw BusinessException.conflict(RejectCategory.WARMUP_TICKET_ACTIVE,
                    "窑 " + request.kilnId() + " 同时刻已存在执行中升温票，新票不予受理");
        }

        List<WarmupStageSpec> specs = request.stages();
        for (int i = 0; i < specs.size(); i++) {
            WarmupStageSpec spec = specs.get(i);
            boolean first = i == 0;
            stageRepository.insert(new WarmupStage(null, ticketId, i + 1, request.curveVersion(),
                    WarmupStageType.HEATING, spec.targetTemp(), spec.maxDurationMinutes(),
                    first ? WarmupStageStatus.ACTIVE : WarmupStageStatus.PENDING,
                    first ? now : null, null, null, null, null, false, null));
        }

        WarmupTicketResponse response = toResponse(ticketRepository.findById(ticketId));
        idempotentRepository.trySave(request.requestId(), "WARMUP_TICKET_CREATE", fingerprint,
                200, writeJson(response), ticketId);
        return response;
    }

    // ---------------------------------------------------------------------
    // 阶段确认（双人复核）
    // ---------------------------------------------------------------------

    /**
     * 当前执行中阶段确认：第一名操作人确认仅登记不生效，第二名不同操作人复核后阶段完成，
     * 并原子推进下一阶段（或走完曲线置票 COMPLETED）。以 requestId 幂等。
     */
    @Transactional
    public WarmupTicketResponse confirm(String ticketNo, WarmupConfirmRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同确认参数，拒绝执行");
            }
            return readJson(existing.responseBody(), WarmupTicketResponse.class);
        }

        WarmupTicket ticket = requireTicket(ticketNo);
        if (ticket.status() != WarmupTicketStatus.EXECUTING) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "升温票 " + ticketNo + " 当前状态 " + ticket.status() + "，曲线已走完，无需确认");
        }
        WarmupStage stage = stageRepository.findActiveByTicket(ticket.id());
        if (stage == null) {
            throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                    "升温票 " + ticketNo + " 当前无执行中阶段");
        }
        if (request.actualTemp().compareTo(stage.targetTemp()) < 0) {
            throw BusinessException.unprocessable(RejectCategory.TARGET_NOT_REACHED,
                    "阶段 " + stage.seq() + " 实测温度 " + request.actualTemp()
                            + "℃ 未达目标温度 " + stage.targetTemp() + "℃，确认不生效");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if (stage.confirmUser1() == null) {
            // 单人确认不生效：仅登记第一确认人，等待复核
            if (stageRepository.markFirstConfirm(stage.id(), request.operatorId()) == 0) {
                throw BusinessException.conflict(RejectCategory.ILLEGAL_STATE,
                        "阶段确认时状态已变化，请重试");
            }
        } else {
            if (stage.confirmUser1().equals(request.operatorId())) {
                throw BusinessException.unprocessable(RejectCategory.ILLEGAL_STATE,
                        "阶段确认须双人复核，操作人 " + request.operatorId() + " 已确认过，单人确认不生效");
            }
            if (stageRepository.markSecondConfirm(stage.id(), request.operatorId(), now) == 0) {
                throw BusinessException.conflict(RejectCategory.ILLEGAL_STATE,
                        "阶段复核时状态已变化，请重试");
            }
            // 阶段推进与曲线版本记录在同一事务内原子完成
            List<WarmupStage> stages = stageRepository.findByTicket(ticket.id());
            boolean hasNext = stages.stream()
                    .anyMatch(s -> s.status() == WarmupStageStatus.PENDING);
            if (hasNext) {
                int nextSeq = stage.seq() + 1;
                stageRepository.activateStage(ticket.id(), nextSeq, now);
                ticketRepository.advanceStage(ticket.id(), nextSeq);
            } else {
                // 曲线走完：票置 COMPLETED 并释放窑升温票槽
                ticketRepository.markCompleted(ticket.id(), now);
                ticketRepository.releaseKiln(ticket.kilnId());
            }
        }

        WarmupTicketResponse response = toResponse(ticketRepository.findById(ticket.id()));
        idempotentRepository.trySave(request.requestId(), "WARMUP_STAGE_CONFIRM", fingerprint,
                200, writeJson(response), ticket.id());
        return response;
    }

    // ---------------------------------------------------------------------
    // 投料（曲线未走完禁止投料）
    // ---------------------------------------------------------------------

    /**
     * 投料请求：与升温曲线状态在同一事务内校验，窑存在曲线未走完的升温票即拒绝。
     * 以 requestId 幂等。
     */
    @Transactional
    public FeedResponse feed(FeedRequest request) {
        String fingerprint = FingerprintUtil.sha256(objectMapper, request.requestId(), request);
        var existing = idempotentRepository.find(request.requestId());
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict(RejectCategory.IDEMPOTENT_CONFLICT,
                        "requestId=" + request.requestId() + " 已用于不同投料参数，拒绝执行");
            }
            return readJson(existing.responseBody(), FeedResponse.class);
        }

        // 同事务内校验曲线状态：先锁窑，再检查执行中升温票
        kilnLockRepository.lockKiln(request.kilnId());
        WarmupTicket executing = ticketRepository.findExecutingByKiln(request.kilnId());
        if (executing != null) {
            throw BusinessException.unprocessable(RejectCategory.CURVE_NOT_FINISHED,
                    "窑 " + request.kilnId() + " 升温曲线未走完（票 " + executing.ticketNo()
                            + " 执行至第 " + executing.currentStageSeq() + " 段），禁止投料");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        feedRepository.insert(request.requestId(), request.kilnId(), request.operatorId(), now);
        FeedResponse response = new FeedResponse(request.requestId(), request.kilnId(),
                request.operatorId(), now, true);
        idempotentRepository.trySave(request.requestId(), "KILN_FEED", fingerprint,
                200, writeJson(response), null);
        return response;
    }

    // ---------------------------------------------------------------------
    // 超时扫描
    // ---------------------------------------------------------------------

    /**
     * 扫描升温票阶段：升温段恰满最长用时未确认自动转保温段；
     * 保温段恰满 4 小时（holding_since + 4h &lt;= now）升级告警。返回本次变更条数。
     */
    @Transactional
    public int scanWarmupTimeouts() {
        LocalDateTime now = LocalDateTime.now(clock);
        int changed = 0;
        for (WarmupStage stage : stageRepository.findHeatingExpired(now)) {
            changed += stageRepository.convertToHolding(stage.id(), now);
        }
        LocalDateTime alarmDeadline = now.minusHours(properties.getWarmupHoldingTimeoutHours());
        for (WarmupStage stage : stageRepository.findHoldingAlarmDue(alarmDeadline)) {
            changed += stageRepository.markAlarm(stage.id(), now);
        }
        return changed;
    }

    // ---------------------------------------------------------------------
    // 查询视图
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public WarmupTicketResponse status(String ticketNo) {
        return toResponse(requireTicket(ticketNo));
    }

    // ---------------------------------------------------------------------
    // 装配辅助
    // ---------------------------------------------------------------------

    private WarmupTicket requireTicket(String ticketNo) {
        WarmupTicket ticket = ticketRepository.findByTicketNo(ticketNo);
        if (ticket == null) {
            throw BusinessException.notFound("升温票 " + ticketNo + " 不存在");
        }
        return ticket;
    }

    private WarmupTicketResponse toResponse(WarmupTicket ticket) {
        List<WarmupStageResponse> stages = stageRepository.findByTicket(ticket.id()).stream()
                .map(s -> new WarmupStageResponse(s.seq(), s.stageType().name(), s.targetTemp(),
                        s.maxDurationMinutes(), s.status().name(), s.enteredAt(), s.holdingSince(),
                        s.confirmUser1(), s.confirmUser2(), s.confirmedAt(), s.alarm(),
                        s.alarmedAt()))
                .toList();
        return new WarmupTicketResponse(ticket.ticketNo(), ticket.kilnId(), ticket.curveVersion(),
                ticket.status().name(), ticket.currentStageSeq(), stages,
                ticket.createdAt(), ticket.completedAt());
    }

    private String generateTicketNo(LocalDateTime now) {
        return "WT" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
                .format(now) + String.format("%03d", (int) (Math.random() * 1000));
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
