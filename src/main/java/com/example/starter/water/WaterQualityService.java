package com.example.starter.water;

import com.example.starter.common.ApiException;
import com.example.starter.config.WaterQualityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 水质采样与复测核心流程：阈值告警、连续越限升级紧急、自动下发处置指令、复测恢复。
 */
@Service
public class WaterQualityService {

    private final WaterRepository repository;
    private final ThresholdEvaluator evaluator;
    private final WaterQualityProperties properties;
    private final Clock clock;

    public WaterQualityService(WaterRepository repository,
                               ThresholdEvaluator evaluator,
                               WaterQualityProperties properties,
                               Clock clock) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 采样指令；sampledAt 为空时取当前时间。
     */
    public record SampleCommand(double dissolvedOxygen, double ammoniaNitrogen, double temperature,
                                Instant sampledAt) {
    }

    /**
     * 采样受理结果。
     */
    public record SampleResult(long sampleId, long tankId, boolean violating, List<String> violations,
                               int consecutiveViolations, TankStatus tankStatus, List<Long> alarmIds,
                               Long disposalOrderId, boolean autoDisposalSuspended) {
    }

    /**
     * 复测结果。
     */
    public record RetestResult(long retestId, long tankId, boolean passed, TankStatus tankStatus,
                               int resolvedAlarms) {
    }

    /**
     * 展示登记结果。
     */
    public record ExhibitResult(long exhibitId, long tankId, String exhibitName) {
    }

    /**
     * 提交一次水质采样：任一指标越限即告警；连续越限恰满配置点数升级紧急并自动下发处置指令。
     * 传感器失联挂起期间仍记录告警，但暂停自动下发处置指令。
     */
    @Transactional
    public SampleResult submitSample(long tankId, SampleCommand command) {
        TankRow tank = repository.lockTank(tankId)
                .orElseThrow(() -> ApiException.notFound("展示池不存在: " + tankId));
        Instant now = clock.instant();
        Instant sampledAt = command.sampledAt() == null ? now : command.sampledAt();

        List<String> violations = evaluator.evaluate(
                command.dissolvedOxygen(), command.ammoniaNitrogen(), command.temperature());
        boolean violating = !violations.isEmpty();
        long sampleId = repository.insertSample(tankId, command.dissolvedOxygen(),
                command.ammoniaNitrogen(), command.temperature(), violating, sampledAt, now);

        List<Long> alarmIds = new ArrayList<>();
        Long disposalOrderId = null;
        int consecutive;
        TankStatus status = tank.status();
        if (violating) {
            consecutive = tank.consecutiveViolations() + 1;
            String metric = String.join(",", violations);
            alarmIds.add(repository.insertAlarm(tankId, AlarmLevel.WARN, metric,
                    "水质指标越限: " + metric, now));
            if (consecutive >= properties.getConsecutiveViolationLimit() && status != TankStatus.EMERGENCY) {
                status = TankStatus.EMERGENCY;
                long emergencyAlarmId = repository.insertAlarm(tankId, AlarmLevel.EMERGENCY, metric,
                        "连续 " + consecutive + " 个采样点越限，升级为紧急状态", now);
                alarmIds.add(emergencyAlarmId);
                if (!tank.autoDisposalSuspended()) {
                    disposalOrderId = issueDisposalOrder(emergencyAlarmId, tankId, violations, now);
                }
            }
        } else {
            consecutive = 0;
        }

        TankRow updated = new TankRow(tank.id(), tank.code(), tank.name(), status,
                status == TankStatus.EMERGENCY || tank.displaySuspended(),
                tank.autoDisposalSuspended(), tank.dataMissing(), consecutive,
                tank.sensorStatus(), tank.sensorLastSeenAt());
        repository.updateTankState(updated);
        return new SampleResult(sampleId, tankId, violating, violations, consecutive, status,
                alarmIds, disposalOrderId, tank.autoDisposalSuspended());
    }

    /**
     * 复测：仅紧急状态可复测；复测记录与状态变更在同一事务内完成，合格后解除告警并恢复展示登记。
     */
    @Transactional
    public RetestResult submitRetest(long tankId, SampleCommand command) {
        TankRow tank = repository.lockTank(tankId)
                .orElseThrow(() -> ApiException.notFound("展示池不存在: " + tankId));
        if (tank.status() != TankStatus.EMERGENCY) {
            throw ApiException.conflict("展示池当前状态无需复测: " + tank.status());
        }
        Instant now = clock.instant();
        boolean passed = evaluator.evaluate(
                command.dissolvedOxygen(), command.ammoniaNitrogen(), command.temperature()).isEmpty();
        long retestId = repository.insertRetest(tankId, command.dissolvedOxygen(),
                command.ammoniaNitrogen(), command.temperature(), passed, now);

        int resolved = 0;
        TankStatus status = tank.status();
        if (passed) {
            status = TankStatus.NORMAL;
            resolved = repository.resolveActiveAlarms(tankId, now);
            TankRow updated = new TankRow(tank.id(), tank.code(), tank.name(), status, false,
                    tank.autoDisposalSuspended(), tank.dataMissing(), 0,
                    tank.sensorStatus(), tank.sensorLastSeenAt());
            repository.updateTankState(updated);
        }
        return new RetestResult(retestId, tankId, passed, status, resolved);
    }

    /**
     * 展示登记：处置期间（紧急状态暂停展示）拒绝登记。
     */
    @Transactional
    public ExhibitResult registerExhibit(long tankId, String exhibitName) {
        TankRow tank = repository.lockTank(tankId)
                .orElseThrow(() -> ApiException.notFound("展示池不存在: " + tankId));
        if (tank.displaySuspended()) {
            throw ApiException.conflict("展示池处于处置期间，已暂停展示登记: " + tank.code());
        }
        long exhibitId = repository.insertExhibit(tankId, exhibitName, clock.instant());
        return new ExhibitResult(exhibitId, tankId, exhibitName);
    }

    /**
     * 下发处置指令：回执确认时限为下发后配置分钟数，恰满未确认即升级。
     */
    long issueDisposalOrder(long alarmId, long tankId, List<String> violations, Instant issuedAt) {
        DisposalType type = evaluator.disposalTypeFor(violations);
        Instant deadline = issuedAt.plus(Duration.ofMinutes(properties.getConfirmTimeoutMinutes()));
        return repository.insertDisposalOrder(alarmId, tankId, type, issuedAt, deadline);
    }
}
