package com.example.starter.tailings.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.tailings.domain.CommandStatus;
import com.example.starter.tailings.domain.PlanStatus;
import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.idempotency.IdempotencyService;
import com.example.starter.tailings.repo.CommandRepository;
import com.example.starter.tailings.repo.PlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 泄流指令链：下达 → 签收 → 执行 → 回执。
 * 签收时限15分钟，恰满15分钟未签收自动升级值班领导；
 * 回执与方案偏差超10%（恰10%视为一致）须附原因说明；
 * 同一尾矿库同时刻仅允许一个执行中泄流方案；回执以 requestId 幂等。
 */
@Service
public class CommandService {

    /** 签收时限：下达后15分钟。 */
    public static final long ACK_TIMEOUT_MINUTES = 15;

    /** 回执偏差一致阈值：恰10%视为一致。 */
    public static final BigDecimal DEVIATION_TOLERANCE_PCT = new BigDecimal("10");

    private final CommandRepository commandRepository;
    private final PlanRepository planRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommandService(CommandRepository commandRepository, PlanRepository planRepository,
                          IdempotencyService idempotencyService, ObjectMapper objectMapper, Clock clock) {
        this.commandRepository = commandRepository;
        this.planRepository = planRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 指令视图。
     */
    public record CommandResult(long commandId, long planId, long pondId, String status,
                                LocalDateTime issuedAt, LocalDateTime ackDeadline, boolean escalated) {
    }

    /**
     * 签收结果。accepted=false 表示签收被拒（超时已升级或状态非法）。
     */
    public record AckResult(boolean accepted, long commandId, String status, boolean escalated,
                            String message) {
    }

    /**
     * 回执结果。
     */
    public record ReceiptResult(long commandId, String requestId, BigDecimal plannedDischarge,
                                BigDecimal actualDischarge, BigDecimal deviationPct,
                                boolean consistent, String status) {
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 下达指令：每个生效方案仅下达一次。
     */
    @Transactional
    public CommandResult issue(String requestId, long planId) {
        String canonical = FloodRoutingService.canonical("planId=" + planId);
        return idempotencyService.execute(requestId, canonical, CommandResult.class,
                () -> doIssue(requestId, planId));
    }

    private CommandResult doIssue(String requestId, long planId) {
        PlanRepository.PlanRow plan = planRepository.findPlan(planId)
                .orElseThrow(() -> BusinessException.notFound("泄流方案不存在: " + planId));
        if (!PlanStatus.EFFECTIVE.name().equals(plan.status())) {
            throw BusinessException.conflict("方案状态为 " + plan.status() + "，不能下达指令");
        }
        LocalDateTime issuedAt = now();
        LocalDateTime deadline = issuedAt.plusMinutes(ACK_TIMEOUT_MINUTES);
        long commandId;
        try {
            commandId = commandRepository.insert(requestId, planId, plan.pondId(),
                    CommandStatus.ISSUED.name(), issuedAt, deadline);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("该方案已下达过指令");
        }
        return new CommandResult(commandId, planId, plan.pondId(), CommandStatus.ISSUED.name(),
                issuedAt, deadline, false);
    }

    /**
     * 签收：恰满15分钟未签收自动升级值班领导（升级落库，签收被拒）。
     */
    @Transactional
    public AckResult ack(long commandId) {
        CommandRepository.CommandRow command = commandRepository.findById(commandId)
                .orElseThrow(() -> BusinessException.notFound("泄流指令不存在: " + commandId));
        if (!CommandStatus.ISSUED.name().equals(command.status())) {
            throw BusinessException.conflict("指令状态为 " + command.status() + "，不能签收");
        }
        LocalDateTime now = now();
        if (!now.isBefore(command.ackDeadline())) {
            commandRepository.updateEscalated(commandId);
            return new AckResult(false, commandId, CommandStatus.ESCALATED.name(), true,
                    "签收超时(15分钟)，已自动升级值班领导");
        }
        commandRepository.updateAcked(commandId, now);
        return new AckResult(true, commandId, CommandStatus.ACKED.name(), false, "签收成功");
    }

    /**
     * 执行：同一尾矿库同时刻仅允许一个执行中泄流方案（pond_executing_plan 主键守卫）。
     */
    @Transactional
    public CommandResult execute(long commandId) {
        CommandRepository.CommandRow command = commandRepository.findById(commandId)
                .orElseThrow(() -> BusinessException.notFound("泄流指令不存在: " + commandId));
        if (!CommandStatus.ACKED.name().equals(command.status())) {
            throw BusinessException.conflict("指令状态为 " + command.status() + "，不能执行");
        }
        try {
            planRepository.insertExecutingGuard(command.pondId(), command.planId(), commandId);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("同一尾矿库同时刻仅允许一个执行中泄流方案");
        }
        LocalDateTime executedAt = now();
        commandRepository.updateExecuting(commandId, executedAt);
        return new CommandResult(commandId, command.planId(), command.pondId(),
                CommandStatus.EXECUTING.name(), command.issuedAt(), command.ackDeadline(),
                command.escalated());
    }

    /**
     * 回执：实际孔口与泄量；与方案偏差超10%（恰10%视为一致）须附原因说明。requestId 幂等。
     */
    @Transactional
    public ReceiptResult receipt(String requestId, long commandId, List<PlanService.OrificeInput> actualOrifices,
                                 BigDecimal actualDischarge, String reason) {
        String canonical = FloodRoutingService.canonical(
                "commandId=" + commandId, "orifices=" + actualOrifices,
                "actual=" + actualDischarge, "reason=" + reason);
        return idempotencyService.execute(requestId, canonical, ReceiptResult.class,
                () -> doReceipt(commandId, actualOrifices, actualDischarge, reason));
    }

    private ReceiptResult doReceipt(long commandId, List<PlanService.OrificeInput> actualOrifices,
                                    BigDecimal actualDischarge, String reason) {
        CommandRepository.CommandRow command = commandRepository.findById(commandId)
                .orElseThrow(() -> BusinessException.notFound("泄流指令不存在: " + commandId));
        if (!CommandStatus.EXECUTING.name().equals(command.status())) {
            throw BusinessException.conflict("指令状态为 " + command.status() + "，不能回执");
        }
        PlanRepository.PlanRow plan = planRepository.findPlan(command.planId())
                .orElseThrow(() -> BusinessException.notFound("泄流方案不存在: " + command.planId()));
        BigDecimal planned = plan.totalDischarge();
        BigDecimal deviationPct = actualDischarge.subtract(planned).abs()
                .multiply(new BigDecimal("100"))
                .divide(planned, 4, RoundingMode.HALF_UP);
        boolean consistent = deviationPct.compareTo(DEVIATION_TOLERANCE_PCT) <= 0;
        if (!consistent && (reason == null || reason.isBlank())) {
            throw BusinessException.badRequest("回执泄量偏差超过10%，须附原因说明");
        }
        String orificesJson;
        try {
            orificesJson = objectMapper.writeValueAsString(actualOrifices);
        } catch (Exception e) {
            throw new IllegalStateException("回执孔口序列化失败", e);
        }
        commandRepository.updateReceipted(commandId, now(), orificesJson, actualDischarge,
                deviationPct, consistent ? null : reason);
        planRepository.deleteExecutingGuard(command.pondId());
        return new ReceiptResult(commandId, command.requestId(), planned, actualDischarge,
                deviationPct, consistent, CommandStatus.RECEIPTED.name());
    }

    /**
     * 查询指令当前状态（读取时惰性检查签收超时升级）。
     */
    @Transactional
    public CommandResult get(long commandId) {
        CommandRepository.CommandRow command = commandRepository.findById(commandId)
                .orElseThrow(() -> BusinessException.notFound("泄流指令不存在: " + commandId));
        if (CommandStatus.ISSUED.name().equals(command.status())
                && !now().isBefore(command.ackDeadline())) {
            commandRepository.updateEscalated(commandId);
            command = commandRepository.findById(commandId).orElseThrow();
        }
        return new CommandResult(command.id(), command.planId(), command.pondId(), command.status(),
                command.issuedAt(), command.ackDeadline(), command.escalated());
    }
}
