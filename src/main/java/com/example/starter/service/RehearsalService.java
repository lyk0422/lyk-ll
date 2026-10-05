package com.example.starter.service;

import com.example.starter.domain.RehearsalVerdict;
import com.example.starter.repo.AlarmHandlingRepository;
import com.example.starter.repo.BudgetRepository;
import com.example.starter.repo.RehearsalRepository;
import com.example.starter.repo.StrategyRepository;
import com.example.starter.support.RehearsalTx;
import com.example.starter.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.StringJoiner;

/**
 * 策略下发前预演与仲裁：
 * <ul>
 *     <li>合成能耗模型（版本 GH-ENERGY-v1）：小时速率 = 0.05 基础负荷
 *         + 0.03×max(0, 白天设定−20℃) + 0.02×max(0, 夜间设定−14℃)
 *         + 0.01×max(0, 湿度设定−60%RH)，单位 kWh/h；</li>
 *     <li>执行期 = 生效时刻到当日 UTC 日界；预估能耗 = 速率×执行期小时数，保留 3 位小数；</li>
 *     <li>预估能耗超过当日剩余预算（预算−已耗）即拒绝，恰等允许；当日未分配预算则不校验能耗；</li>
 *     <li>同分区存在进行中告警处置时，新策略昼夜温度与湿度设定点须全部落在处置窗口内
 *         （边界含等号），否则仲裁为挂起；处置冲突优先于能耗拒绝；</li>
 *     <li>预演输入与结论整体留档，随策略版本保存，可按同版本模型复算。</li>
 * </ul>
 */
@Service
public class RehearsalService {

    /** 合成能耗模型版本号，复算时须使用同版本系数。 */
    public static final String MODEL_VERSION = "GH-ENERGY-v1";
    /** 基础小时负荷，单位 kWh/h。 */
    static final BigDecimal BASE_RATE = new BigDecimal("0.05");
    /** 白天设定点相对 20℃ 的加热系数，单位 kWh/h/℃。 */
    static final BigDecimal DAY_HEAT_COEFF = new BigDecimal("0.03");
    /** 夜间设定点相对 14℃ 的加热系数，单位 kWh/h/℃。 */
    static final BigDecimal NIGHT_HEAT_COEFF = new BigDecimal("0.02");
    /** 湿度设定点相对 60%RH 的加湿系数，单位 kWh/h/%RH。 */
    static final BigDecimal HUMIDITY_COEFF = new BigDecimal("0.01");
    static final BigDecimal DAY_BASELINE = new BigDecimal("20.00");
    static final BigDecimal NIGHT_BASELINE = new BigDecimal("14.00");
    static final BigDecimal HUMIDITY_BASELINE = new BigDecimal("60.00");

    private final RehearsalRepository rehearsalRepository;
    private final BudgetRepository budgetRepository;
    private final AlarmHandlingRepository alarmHandlingRepository;
    private final RehearsalTx rehearsalTx;
    private final Clock clock;

    public RehearsalService(RehearsalRepository rehearsalRepository, BudgetRepository budgetRepository,
                            AlarmHandlingRepository alarmHandlingRepository, RehearsalTx rehearsalTx,
                            Clock clock) {
        this.rehearsalRepository = rehearsalRepository;
        this.budgetRepository = budgetRepository;
        this.alarmHandlingRepository = alarmHandlingRepository;
        this.rehearsalTx = rehearsalTx;
        this.clock = clock;
    }

    /**
     * 执行预演仲裁，返回未落库的留档记录（调用方按结论在同一事务或独立事务内落档）。
     */
    public RehearsalRepository.Rehearsal arbitrate(StrategyRepository.Strategy strategy,
                                                   Instant effectiveTime, String requestId) {
        LocalDate evalDate = effectiveTime.atZone(ZoneOffset.UTC).toLocalDate();
        long durationMinutes = Duration.between(effectiveTime,
                evalDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)).toMinutes();
        BigDecimal hourlyRate = hourlyRate(strategy.dayTemp(), strategy.nightTemp(),
                strategy.humiditySetpoint());
        BigDecimal estimated = hourlyRate
                .multiply(BigDecimal.valueOf(durationMinutes))
                .divide(BigDecimal.valueOf(60), 3, RoundingMode.HALF_UP);

        BigDecimal budgetKwh = null;
        BigDecimal consumedKwh = null;
        BigDecimal remainingKwh = null;
        var budget = budgetRepository.find(strategy.zoneCode(), evalDate);
        if (budget.isPresent()) {
            budgetKwh = budget.get().budgetKwh();
            consumedKwh = budget.get().consumedKwh();
            remainingKwh = budget.get().remaining();
        }

        String handlingConflict = incompatibleHandlings(strategy);

        RehearsalVerdict verdict;
        if (handlingConflict != null) {
            verdict = RehearsalVerdict.SUSPENDED;
        } else if (remainingKwh != null && estimated.compareTo(remainingKwh) > 0) {
            verdict = RehearsalVerdict.REJECTED;
        } else {
            verdict = RehearsalVerdict.PASS;
        }
        return new RehearsalRepository.Rehearsal(
                null, strategy.strategyCode(), requestId, MODEL_VERSION, effectiveTime, evalDate,
                strategy.dayTemp(), strategy.nightTemp(), strategy.humiditySetpoint(),
                durationMinutes, hourlyRate, estimated, budgetKwh, consumedKwh, remainingKwh,
                handlingConflict, verdict.name(), clock.instant());
    }

    /**
     * 在调用方事务内落档（PASS/SUSPENDED：与策略状态推进同事务）。
     */
    public void archive(RehearsalRepository.Rehearsal rehearsal) {
        rehearsalRepository.insert(rehearsal);
    }

    /**
     * 在独立事务内落档（REJECTED：业务事务随拒绝回滚，留档仍须可查）。
     */
    public void archiveIsolated(RehearsalRepository.Rehearsal rehearsal) {
        rehearsalTx.insertInNewTransaction(rehearsal);
    }

    @Transactional(readOnly = true)
    public RehearsalRepository.Rehearsal latest(String strategyCode) {
        return rehearsalRepository.findLatestByStrategy(strategyCode)
                .orElseThrow(() -> new NotFoundException("策略尚无预演留档：" + strategyCode));
    }

    /**
     * 合成能耗模型小时速率（kWh/h），公开静态以便复算校验。
     */
    public static BigDecimal hourlyRate(BigDecimal dayTemp, BigDecimal nightTemp,
                                        BigDecimal humiditySetpoint) {
        return BASE_RATE
                .add(DAY_HEAT_COEFF.multiply(dayTemp.subtract(DAY_BASELINE).max(BigDecimal.ZERO)))
                .add(NIGHT_HEAT_COEFF.multiply(nightTemp.subtract(NIGHT_BASELINE).max(BigDecimal.ZERO)))
                .add(HUMIDITY_COEFF.multiply(humiditySetpoint.subtract(HUMIDITY_BASELINE).max(BigDecimal.ZERO)));
    }

    /**
     * 同分区进行中处置的不兼容清单；全部兼容或无进行中处置时返回 null。
     */
    private String incompatibleHandlings(StrategyRepository.Strategy strategy) {
        List<AlarmHandlingRepository.Handling> handlings =
                alarmHandlingRepository.findInProgressByZone(strategy.zoneCode());
        StringJoiner conflicts = new StringJoiner(",");
        for (AlarmHandlingRepository.Handling h : handlings) {
            if (!isCompatible(strategy, h)) {
                conflicts.add(h.handlingCode());
            }
        }
        String joined = conflicts.toString();
        return joined.isEmpty() ? null : joined;
    }

    /**
     * 兼容性判定：昼夜温度设定点均落在处置温度窗口内、湿度设定点落在处置湿度窗口内，边界含等号。
     */
    static boolean isCompatible(StrategyRepository.Strategy s, AlarmHandlingRepository.Handling h) {
        return within(s.dayTemp(), h.tempMin(), h.tempMax())
                && within(s.nightTemp(), h.tempMin(), h.tempMax())
                && within(s.humiditySetpoint(), h.humidityMin(), h.humidityMax());
    }

    private static boolean within(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
