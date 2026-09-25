package com.example.starter.service;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.Assessment;
import com.example.starter.domain.HeatingUnit;
import com.example.starter.domain.Valve;
import com.example.starter.domain.ValveVersion;
import com.example.starter.repository.AdjustmentTaskRepository;
import com.example.starter.repository.AssessmentRepository;
import com.example.starter.repository.StationRepository;
import com.example.starter.repository.UnitRepository;
import com.example.starter.repository.ValveRepository;
import com.example.starter.repository.ValveVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * 调节流转核心服务：初调节/再调节指令受理、效果评估、归档终态。
 */
@Service
public class AdjustmentService {

    /** 单次步进幅度上限：不超过 10%（恰 10% 合法）。 */
    public static final BigDecimal MAX_STEP_PERCENT = new BigDecimal("10");
    /** 稳定期：调节后须稳定 4 小时再评估（恰满 4 小时允许评估）。 */
    public static final Duration STABILIZATION = Duration.ofHours(4);
    /** 24 小时（UTC）窗口内单单元最多调节 3 次（恰 3 次合法），超出转人工复核。 */
    public static final int MAX_ADJUSTMENTS_PER_24H = 3;
    /** 24 小时窗口长度。 */
    public static final Duration ADJUSTMENT_WINDOW = Duration.ofHours(24);

    private final StationRepository stationRepository;
    private final UnitRepository unitRepository;
    private final ValveRepository valveRepository;
    private final ValveVersionRepository versionRepository;
    private final AdjustmentTaskRepository taskRepository;
    private final AssessmentRepository assessmentRepository;
    private final BalanceService balanceService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdjustmentService(StationRepository stationRepository,
                             UnitRepository unitRepository,
                             ValveRepository valveRepository,
                             ValveVersionRepository versionRepository,
                             AdjustmentTaskRepository taskRepository,
                             AssessmentRepository assessmentRepository,
                             BalanceService balanceService,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.stationRepository = stationRepository;
        this.unitRepository = unitRepository;
        this.valveRepository = valveRepository;
        this.versionRepository = versionRepository;
        this.taskRepository = taskRepository;
        this.assessmentRepository = assessmentRepository;
        this.balanceService = balanceService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 下达调节令：幂等受理，站内同时刻只允许一个执行中任务；
     * 阀门开度写入与版本记录在同一事务内完成。
     *
     * @param stationId   换热站 ID
     * @param requestId   客户端幂等键
     * @param unitId      目标单元 ID
     * @param stepPercent 步进幅度，单位 %，0 &lt; step &le; 10
     * @return 已受理（或幂等重放的首次）任务
     */
    @Transactional
    public AdjustmentTask issueAdjustment(long stationId, String requestId, long unitId,
                                          BigDecimal stepPercent) {
        String fingerprint = fingerprint("ADJ", requestId, String.valueOf(stationId),
                String.valueOf(unitId), stepPercent.stripTrailingZeros().toPlainString());
        var existing = taskRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        if (stepPercent == null || stepPercent.compareTo(BigDecimal.ZERO) <= 0
                || stepPercent.compareTo(MAX_STEP_PERCENT) > 0) {
            throw new UnprocessableException("单次步进幅度须为 (0, 10]%，当前: " + stepPercent);
        }
        // 行级锁串行化同一换热站的任务受理，保证并发下唯一受理。
        stationRepository.lockById(stationId)
                .orElseThrow(() -> new NotFoundException("换热站不存在: stationId=" + stationId));
        // 持锁后重查幂等键：并发下同键请求在前序事务提交后按重放处理。
        var existingAfterLock = taskRepository.findByRequestId(requestId);
        if (existingAfterLock.isPresent()) {
            return replayOrConflict(existingAfterLock.get(), fingerprint);
        }
        HeatingUnit unit = unitRepository.findById(unitId)
                .filter(u -> u.stationId() == stationId)
                .orElseThrow(() -> new NotFoundException(
                        "单元不存在或不属于该换热站: unitId=" + unitId));
        if (taskRepository.existsActiveByStationId(stationId)) {
            throw new ConflictException("换热站存在执行中的调节任务，并发下达唯一受理: stationId=" + stationId);
        }
        Instant now = clock.instant();
        int executed = taskRepository.countExecutedSince(unitId, now.minus(ADJUSTMENT_WINDOW));
        if (executed >= MAX_ADJUSTMENTS_PER_24H) {
            AdjustmentTask review = new AdjustmentTask(0, stationId, unitId, requestId, fingerprint,
                    null, null, null, null, AdjustmentTask.TaskStatus.MANUAL_REVIEW,
                    null, null, null, now);
            return taskRepository.findById(taskRepository.insert(review)).orElseThrow();
        }
        BalanceService.BalanceView balance = balanceService.evaluateBalance(stationId, now);
        AdjustmentTask.ImbalanceType classification = balance.units().stream()
                .filter(u -> u.unitId() == unit.id())
                .map(BalanceService.UnitTemperature::classification)
                .findFirst()
                .orElseThrow(() -> new UnprocessableException(
                        "单元无温度数据或未失衡，无法判定调节方向: unitId=" + unitId));
        Valve valve = valveRepository.findByUnitId(unitId)
                .orElseThrow(() -> new NotFoundException("单元未配置阀门: unitId=" + unitId));
        BigDecimal oldOpening = valve.openingPercent();
        BigDecimal newOpening = classification == AdjustmentTask.ImbalanceType.UNDERFLOW
                ? oldOpening.add(stepPercent) : oldOpening.subtract(stepPercent);
        if (newOpening.compareTo(BigDecimal.ZERO) < 0
                || newOpening.compareTo(new BigDecimal("100")) > 0) {
            throw new UnprocessableException("调节后开度越界 [0,100]%: " + newOpening);
        }
        AdjustmentTask task = new AdjustmentTask(0, stationId, unitId, requestId, fingerprint,
                classification, stepPercent, oldOpening, newOpening,
                AdjustmentTask.TaskStatus.EXECUTING, now, null, null, now);
        long taskId;
        try {
            taskId = taskRepository.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发下同键插入：唯一约束保证只受理一次，重放首个结果。
            return taskRepository.findByRequestId(requestId)
                    .map(t -> replayOrConflict(t, fingerprint))
                    .orElseThrow(() -> e);
        }
        // 开度写入与版本记录同事务提交，任一失败整体回滚。
        valveRepository.updateOpening(valve.id(), newOpening, now);
        versionRepository.insert(valve.id(), taskId,
                versionRepository.maxVersionNo(valve.id()) + 1, oldOpening, newOpening, now);
        return taskRepository.findById(taskId).orElseThrow();
    }

    /**
     * 效果评估：调节稳定满 4 小时（恰满允许）后执行，幂等键为 requestId。
     */
    @Transactional
    public Assessment assess(long taskId, String requestId) {
        String fingerprint = fingerprint("ASM", requestId, String.valueOf(taskId));
        var existing = assessmentRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            Assessment found = existing.get();
            if (!found.fingerprint().equals(fingerprint)) {
                throw new ConflictException("同键异参，返回 409: requestId=" + requestId);
            }
            return found;
        }
        AdjustmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("调节任务不存在: taskId=" + taskId));
        switch (task.status()) {
            case EXECUTING -> {
                // 可评估
            }
            case ASSESSED -> throw new ConflictException("任务已评估: taskId=" + taskId);
            case ARCHIVED -> throw new ConflictException("任务已归档（终态），不允许评估: taskId=" + taskId);
            case MANUAL_REVIEW -> throw new ConflictException("任务已转人工复核，不允许评估: taskId=" + taskId);
            default -> throw new IllegalStateException("未知任务状态: " + task.status());
        }
        Instant now = clock.instant();
        Instant stabilizedAt = task.executedAt().plus(STABILIZATION);
        if (now.isBefore(stabilizedAt)) {
            throw new UnprocessableException("稳定期未满 4 小时，可评估时间(UTC): " + stabilizedAt);
        }
        BalanceService.BalanceView before = balanceService.evaluateBalance(
                task.stationId(), task.executedAt());
        BalanceService.BalanceView after = balanceService.evaluateBalance(task.stationId(), now);
        String detail = toJson(new Comparison(before, after));
        Assessment assessment = new Assessment(0, taskId, requestId, fingerprint,
                before.diff(), after.diff(), after.balanced(), detail, now);
        try {
            assessmentRepository.insert(assessment);
        } catch (DuplicateKeyException e) {
            return assessmentRepository.findByRequestId(requestId)
                    .filter(a -> a.fingerprint().equals(fingerprint))
                    .orElseThrow(() -> new ConflictException("同键异参，返回 409: requestId=" + requestId));
        }
        taskRepository.updateStatus(taskId, AdjustmentTask.TaskStatus.ASSESSED, now, null);
        return assessmentRepository.findByRequestId(requestId).orElseThrow();
    }

    /**
     * 归档：评估归档为终态，重复归档返回幂等成功。
     */
    @Transactional
    public AdjustmentTask archive(long taskId) {
        AdjustmentTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("调节任务不存在: taskId=" + taskId));
        switch (task.status()) {
            case ARCHIVED -> {
                return task;
            }
            case ASSESSED, MANUAL_REVIEW -> {
                taskRepository.updateStatus(taskId, AdjustmentTask.TaskStatus.ARCHIVED,
                        null, clock.instant());
                return taskRepository.findById(taskId).orElseThrow();
            }
            case EXECUTING -> throw new ConflictException("任务未评估，不允许归档: taskId=" + taskId);
            default -> throw new IllegalStateException("未知任务状态: " + task.status());
        }
    }

    /**
     * 查询任务详情。
     */
    @Transactional(readOnly = true)
    public AdjustmentTask getTask(long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("调节任务不存在: taskId=" + taskId));
    }

    /**
     * 查询任务的评估记录。
     */
    @Transactional(readOnly = true)
    public Assessment getAssessmentByTask(long taskId) {
        return assessmentRepository.findByTaskId(taskId)
                .orElseThrow(() -> new NotFoundException("任务尚未评估: taskId=" + taskId));
    }

    /**
     * 查询单元阀门完整版本链，可复算开度变迁。
     */
    @Transactional(readOnly = true)
    public List<ValveVersion> getValveVersions(long unitId) {
        Valve valve = valveRepository.findByUnitId(unitId)
                .orElseThrow(() -> new NotFoundException("单元未配置阀门: unitId=" + unitId));
        return versionRepository.findByValveId(valve.id());
    }

    private AdjustmentTask replayOrConflict(AdjustmentTask existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("同键异参，返回 409 且不改变阀门开度: requestId="
                    + existing.requestId());
        }
        return existing;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("评估快照序列化失败", e);
        }
    }

    /**
     * 前后温度分布对比，用于评估快照与复算。
     */
    public record Comparison(BalanceService.BalanceView before, BalanceService.BalanceView after) {
    }

    static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
