package com.example.starter.service;

import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.domain.ArbitrationDecision;
import com.example.starter.domain.StrategyStatus;
import com.example.starter.repo.CropRepository;
import com.example.starter.repo.RehearsalRepository;
import com.example.starter.repo.StrategyRepository;
import com.example.starter.support.IdempotencySupport;
import com.example.starter.web.BusinessRuleException;
import com.example.starter.web.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 气候策略编制、校验与下发。
 * <ul>
 *     <li>温湿度设定点须在作物生育期允许窗口内，恰在边界合法；</li>
 *     <li>昼夜设定差绝对值不得超过 8 摄氏度，恰 8 合法；</li>
 *     <li>相邻两次变更（生效时刻）间隔不少于 30 分钟，恰满 30 分钟允许；</li>
 *     <li>同一分区同时刻只允许一个生效策略，新策略生效与旧策略失效在同一事务完成；</li>
 *     <li>下发前预演：按合成能耗模型估算执行期能耗，超过当日剩余预算直接拒绝（恰等允许）；</li>
 *     <li>同分区存在进行中告警处置时，新策略须与之兼容，否则挂起等待处置完结，挂起无副作用；</li>
 *     <li>仲裁结论与策略状态推进在同一事务内完成，预演结果随策略版本留档可复算。</li>
 * </ul>
 */
@Service
public class StrategyService {

    /** 昼夜设定差上限（摄氏度），含等号。 */
    static final BigDecimal MAX_DAY_NIGHT_GAP = new BigDecimal("8.00");
    /** 相邻两次策略变更的最小间隔，含等号。 */
    static final Duration MIN_CHANGE_INTERVAL = Duration.ofMinutes(30);

    private final StrategyRepository strategyRepository;
    private final CropRepository cropRepository;
    private final SetupService setupService;
    private final RehearsalService rehearsalService;
    private final RehearsalRepository rehearsalRepository;
    private final IdempotencySupport idempotencySupport;
    private final Clock clock;

    public StrategyService(StrategyRepository strategyRepository, CropRepository cropRepository,
                           SetupService setupService, RehearsalService rehearsalService,
                           RehearsalRepository rehearsalRepository,
                           IdempotencySupport idempotencySupport, Clock clock) {
        this.strategyRepository = strategyRepository;
        this.cropRepository = cropRepository;
        this.setupService = setupService;
        this.rehearsalService = rehearsalService;
        this.rehearsalRepository = rehearsalRepository;
        this.idempotencySupport = idempotencySupport;
        this.clock = clock;
    }

    /**
     * 编制并校验策略；校验通过落为 VALIDATED，失败抛 422 且不落库。
     */
    @Transactional
    public StrategyDtos.StrategyResponse draft(StrategyDtos.DraftRequest request) {
        setupService.requireZone(request.zoneCode());
        CropRepository.StageWindow window = cropRepository
                .findStage(request.cropCode(), request.stageCode())
                .orElseThrow(() -> new NotFoundException("作物生育期不存在："
                        + request.cropCode() + "/" + request.stageCode()));
        validateWindow(request, window);

        Instant now = clock.instant();
        StrategyRepository.Strategy strategy = new StrategyRepository.Strategy(
                null, request.strategyCode(), request.zoneCode(), request.cropCode(), request.stageCode(),
                scaled(request.dayTemp()), scaled(request.nightTemp()), scaled(request.humiditySetpoint()),
                request.tempTolerance(), request.humidityTolerance(),
                StrategyStatus.VALIDATED.name(), null, null, now, now, null, null);
        strategyRepository.insertDraft(strategy, now);
        return toResponse(strategy);
    }

    private void validateWindow(StrategyDtos.DraftRequest request, CropRepository.StageWindow window) {
        if (less(request.dayTemp(), window.dayTempMin()) || greater(request.dayTemp(), window.dayTempMax())) {
            throw new BusinessRuleException("白天温度设定点 " + request.dayTemp()
                    + " 超出生育期允许窗口 [" + window.dayTempMin() + ", " + window.dayTempMax() + "]");
        }
        if (less(request.nightTemp(), window.nightTempMin())
                || greater(request.nightTemp(), window.nightTempMax())) {
            throw new BusinessRuleException("夜间温度设定点 " + request.nightTemp()
                    + " 超出生育期允许窗口 [" + window.nightTempMin() + ", " + window.nightTempMax() + "]");
        }
        if (less(request.humiditySetpoint(), window.humidityMin())
                || greater(request.humiditySetpoint(), window.humidityMax())) {
            throw new BusinessRuleException("湿度设定点 " + request.humiditySetpoint()
                    + " 超出生育期允许窗口 [" + window.humidityMin() + ", " + window.humidityMax() + "]");
        }
        BigDecimal gap = request.dayTemp().subtract(request.nightTemp()).abs();
        if (gap.compareTo(MAX_DAY_NIGHT_GAP) > 0) {
            throw new BusinessRuleException("昼夜设定差 " + gap + "℃ 超过上限 8℃");
        }
        if (request.tempTolerance().signum() < 0 || request.humidityTolerance().signum() < 0) {
            throw new BusinessRuleException("允许带宽不得为负");
        }
    }

    /**
     * 下发策略并原子替换旧策略；以 requestId 幂等。
     */
    @Transactional
    public StrategyDtos.StrategyResponse dispatch(String strategyCode, StrategyDtos.DispatchRequest request) {
        record DispatchPayload(String strategyCode, Instant effectiveTime) {
        }
        return idempotencySupport.execute(request.requestId(), "STRATEGY_DISPATCH",
                new DispatchPayload(strategyCode, request.effectiveTime()),
                StrategyDtos.StrategyResponse.class, 200,
                () -> doDispatch(strategyCode, request));
    }

    private StrategyDtos.StrategyResponse doDispatch(String strategyCode,
                                                      StrategyDtos.DispatchRequest request) {
        StrategyRepository.Strategy strategy = strategyRepository.findByCode(strategyCode)
                .orElseThrow(() -> new NotFoundException("策略不存在：" + strategyCode));
        // SUSPENDED（告警处置冲突挂起）未产生任何副作用，处置完结后可再次下发。
        if (!"VALIDATED".equals(strategy.status()) && !"SUSPENDED".equals(strategy.status())) {
            throw new BusinessRuleException("仅校验通过或挂起等待的策略可下发，当前状态：" + strategy.status());
        }
        Instant effectiveTime = request.effectiveTime();
        // 锁定分区行，串行化同分区并发下发，保证间隔判定、仲裁与唯一生效不被并发穿透。
        strategyRepository.lockZone(strategy.zoneCode());

        // 预演：估算能耗超过当日剩余预算直接拒绝；恰等允许；未分配预算不拦截。
        // 拒绝路径无状态推进，留档独立事务提交，随策略版本可查。
        RehearsalService.Estimate estimate = rehearsalService.estimate(strategy, effectiveTime);
        if (estimate.remainingKwh() != null
                && estimate.estimatedKwh().compareTo(estimate.remainingKwh()) > 0) {
            String reason = "预演估算能耗 " + estimate.estimatedKwh()
                    + "kWh 超过当日剩余预算 " + estimate.remainingKwh() + "kWh，拒绝下发";
            rehearsalService.archiveRejected(strategy, request.requestId(), effectiveTime, estimate, reason);
            throw new BusinessRuleException(reason);
        }

        // 仲裁：进行中告警处置与新策略不兼容时挂起等待处置完结；结论与状态推进同一事务。
        var incompatible = rehearsalService.findIncompatibleAlarm(strategy);
        if (incompatible.isPresent()) {
            rehearsalService.archive(strategy, request.requestId(), effectiveTime, estimate,
                    ArbitrationDecision.SUSPENDED_ALARM,
                    "与进行中告警处置不兼容（告警 #" + incompatible.get().id() + "），挂起等待处置完结");
            strategyRepository.markSuspended(strategy.id());
            return toResponse(strategyRepository.findByCode(strategyCode).orElseThrow());
        }

        strategyRepository.findLatestEffectiveTime(strategy.zoneCode()).ifPresent(previous -> {
            Duration interval = Duration.between(previous, effectiveTime);
            if (interval.abs().compareTo(MIN_CHANGE_INTERVAL) < 0) {
                throw new BusinessRuleException("距上次策略变更间隔 " + interval.toMinutes()
                        + " 分钟，少于最小间隔 30 分钟");
            }
        });
        Instant now = clock.instant();
        // 新生效与旧失效必须同一事务：先将当前生效策略置 EXPIRED，再置新策略 DISPATCHED。
        strategyRepository.findActiveByZone(strategy.zoneCode()).ifPresent(active -> {
            if (!active.id().equals(strategy.id())) {
                strategyRepository.expireStrategy(active.id(), effectiveTime);
            }
        });
        strategyRepository.markDispatched(strategy.id(), request.requestId(), effectiveTime, now);
        rehearsalService.archive(strategy, request.requestId(), effectiveTime, estimate,
                ArbitrationDecision.APPROVED, "预演通过，策略已下发");
        return toResponse(strategyRepository.findByCode(strategyCode).orElseThrow());
    }

    @Transactional(readOnly = true)
    public StrategyDtos.StrategyResponse get(String strategyCode) {
        return strategyRepository.findByCode(strategyCode)
                .map(this::toResponse)
                .orElseThrow(() -> new NotFoundException("策略不存在：" + strategyCode));
    }

    /**
     * 查询该策略全部预演留档（每次下发尝试一行，含拒绝与挂起结论），按留档顺序返回。
     */
    @Transactional(readOnly = true)
    public List<StrategyDtos.RehearsalResponse> rehearsals(String strategyCode) {
        strategyRepository.findByCode(strategyCode)
                .orElseThrow(() -> new NotFoundException("策略不存在：" + strategyCode));
        return rehearsalRepository.findByStrategyCode(strategyCode).stream()
                .map(this::toRehearsalResponse)
                .toList();
    }

    private static boolean less(BigDecimal value, BigDecimal bound) {
        return value.compareTo(bound) < 0;
    }

    private static boolean greater(BigDecimal value, BigDecimal bound) {
        return value.compareTo(bound) > 0;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(2);
    }

    private StrategyDtos.StrategyResponse toResponse(StrategyRepository.Strategy s) {
        return new StrategyDtos.StrategyResponse(
                s.strategyCode(), s.zoneCode(), s.cropCode(), s.stageCode(),
                s.dayTemp(), s.nightTemp(), s.humiditySetpoint(),
                s.tempTolerance(), s.humidityTolerance(),
                s.status(), s.requestId(), s.effectiveTime(),
                s.createdAt(), s.dispatchedAt(), s.expiredAt());
    }

    private StrategyDtos.RehearsalResponse toRehearsalResponse(RehearsalRepository.Rehearsal r) {
        return new StrategyDtos.RehearsalResponse(
                r.id(), r.strategyCode(), r.requestId(), r.effectiveTime(), r.execMinutes(),
                r.estimatedKwh(), r.budgetDate(), r.budgetKwh(), r.remainingKwh(),
                r.decision(), r.reason(), r.createdAt());
    }
}
