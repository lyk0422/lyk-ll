package com.example.starter.service;

import com.example.starter.domain.ArbitrationDecision;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.repo.BudgetRepository;
import com.example.starter.repo.RehearsalRepository;
import com.example.starter.repo.StrategyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 下发前预演与策略冲突仲裁：
 * <ul>
 *     <li>合成能耗模型：执行期 = 生效时刻到次日 UTC 零点；每小时负荷 =
 *     基础负荷 0.200kW + 0.050kW/℃ × max(0, 昼夜设定点均值 − 合成环境温度 18℃)，
 *     估算能耗 = 时均负荷 × 执行期分钟数 / 60（保留 3 位小数）；</li>
 *     <li>估算超过当日剩余预算直接拒绝（恰等允许）；当日未分配预算不拦截，留档预算字段为空；</li>
 *     <li>同分区存在进行中（ACTIVE）告警处置时，新策略三个设定带（昼温/夜温/湿度）均不宽于
 *     告警策略对应带（新带 ⊆ 旧带，处置依据保留）才兼容，否则挂起等待处置完结；</li>
 *     <li>每次下发尝试的预演结论随策略版本留档，可按上述模型复算。</li>
 * </ul>
 */
@Service
public class RehearsalService {

    /** 合成环境温度（℃），设定点均值高于该温度产生温控负荷。 */
    static final BigDecimal AMBIENT_TEMP = new BigDecimal("18.00");
    /** 执行期基础负荷（kW）。 */
    static final BigDecimal BASE_LOAD_KW = new BigDecimal("0.200");
    /** 温控负荷系数（kW/℃）。 */
    static final BigDecimal HEAT_KW_PER_DEGREE = new BigDecimal("0.050");

    private final RehearsalRepository rehearsalRepository;
    private final BudgetRepository budgetRepository;
    private final AlarmRepository alarmRepository;
    private final StrategyRepository strategyRepository;
    private final Clock clock;

    public RehearsalService(RehearsalRepository rehearsalRepository, BudgetRepository budgetRepository,
                            AlarmRepository alarmRepository, StrategyRepository strategyRepository,
                            Clock clock) {
        this.rehearsalRepository = rehearsalRepository;
        this.budgetRepository = budgetRepository;
        this.alarmRepository = alarmRepository;
        this.strategyRepository = strategyRepository;
        this.clock = clock;
    }

    /**
     * 预演估算结果。budgetKwh/remainingKwh 为空表示当日未分配预算。
     */
    public record Estimate(long execMinutes, BigDecimal estimatedKwh, LocalDate budgetDate,
                           BigDecimal budgetKwh, BigDecimal remainingKwh) {
    }

    /**
     * 按合成能耗模型估算执行期能耗，并取当日预算快照（只读，不写库）。
     */
    public Estimate estimate(StrategyRepository.Strategy strategy, Instant effectiveTime) {
        LocalDate budgetDate = effectiveTime.atZone(ZoneOffset.UTC).toLocalDate();
        Instant dayEnd = budgetDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long execMinutes = Duration.between(effectiveTime, dayEnd).toMinutes();

        BigDecimal avgSetpoint = strategy.dayTemp().add(strategy.nightTemp())
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        BigDecimal demand = avgSetpoint.subtract(AMBIENT_TEMP).max(BigDecimal.ZERO);
        BigDecimal hourlyKw = BASE_LOAD_KW.add(HEAT_KW_PER_DEGREE.multiply(demand));
        BigDecimal estimated = hourlyKw.multiply(BigDecimal.valueOf(execMinutes))
                .divide(BigDecimal.valueOf(60), 3, RoundingMode.HALF_UP);

        Optional<BudgetRepository.Budget> budget = budgetRepository.find(strategy.zoneCode(), budgetDate);
        return new Estimate(execMinutes, estimated, budgetDate,
                budget.map(BudgetRepository.Budget::budgetKwh).orElse(null),
                budget.map(BudgetRepository.Budget::remaining).orElse(null));
    }

    /**
     * 找出与新策略不兼容的进行中告警处置：新策略任一设定带宽于告警策略对应带即不兼容。
     * 无进行中告警时返回空。
     */
    public Optional<AlarmRepository.Alarm> findIncompatibleAlarm(StrategyRepository.Strategy newStrategy) {
        List<AlarmRepository.Alarm> active = alarmRepository.findActive(newStrategy.zoneCode());
        for (AlarmRepository.Alarm alarm : active) {
            Optional<StrategyRepository.Strategy> handling =
                    strategyRepository.findByCode(alarm.strategyCode());
            if (handling.isEmpty() || !isCompatible(newStrategy, handling.get())) {
                return Optional.of(alarm);
            }
        }
        return Optional.empty();
    }

    /**
     * 兼容判定：新策略的昼温/夜温/湿度三个设定带均不宽于告警策略对应带（新带 ⊆ 旧带），
     * 即偏离处置依据在新策略下仍然成立。恰相等（同一设定带）兼容。
     */
    static boolean isCompatible(StrategyRepository.Strategy newStrategy,
                                StrategyRepository.Strategy handlingStrategy) {
        return bandContained(newStrategy.dayTemp(), newStrategy.tempTolerance(),
                handlingStrategy.dayTemp(), handlingStrategy.tempTolerance())
                && bandContained(newStrategy.nightTemp(), newStrategy.tempTolerance(),
                handlingStrategy.nightTemp(), handlingStrategy.tempTolerance())
                && bandContained(newStrategy.humiditySetpoint(), newStrategy.humidityTolerance(),
                handlingStrategy.humiditySetpoint(), handlingStrategy.humidityTolerance());
    }

    private static boolean bandContained(BigDecimal newCenter, BigDecimal newTolerance,
                                         BigDecimal oldCenter, BigDecimal oldTolerance) {
        return newCenter.subtract(oldCenter).abs().add(newTolerance).compareTo(oldTolerance) <= 0;
    }

    /**
     * 在调用方事务内留档（通过/挂起路径：仲裁结论与策略状态推进同一事务提交或回滚）。
     */
    public void archive(StrategyRepository.Strategy strategy, String requestId, Instant effectiveTime,
                        Estimate estimate, ArbitrationDecision decision, String reason) {
        rehearsalRepository.insert(new RehearsalRepository.Rehearsal(
                null, strategy.strategyCode(), requestId, effectiveTime, estimate.execMinutes(),
                estimate.estimatedKwh(), estimate.budgetDate(), estimate.budgetKwh(),
                estimate.remainingKwh(), decision.name(), reason, clock.instant()));
    }

    /**
     * 拒绝路径留档：业务事务随即回滚，留档须独立事务提交，保证预演结果随策略版本可查。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void archiveRejected(StrategyRepository.Strategy strategy, String requestId,
                                Instant effectiveTime, Estimate estimate, String reason) {
        archive(strategy, requestId, effectiveTime, estimate, ArbitrationDecision.REJECTED_BUDGET, reason);
    }
}
