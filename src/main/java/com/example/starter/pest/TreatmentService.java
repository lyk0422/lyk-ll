package com.example.starter.pest;

import static com.example.starter.common.IdempotencyService.fingerprint;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotencyService;
import com.example.starter.pest.InventoryRepository.Pesticide;
import com.example.starter.pest.TreatmentChainRepository.Chain;
import com.example.starter.pest.api.Requests.DispenseRequest;
import com.example.starter.pest.api.Requests.LiftApplyRequest;
import com.example.starter.pest.api.Requests.LiftConfirmRequest;
import com.example.starter.pest.api.Requests.TraceRequest;
import com.example.starter.pest.api.Results.ChainView;
import com.example.starter.pest.api.Results.DispenseResult;
import com.example.starter.pest.api.Results.LiftApplyResult;
import com.example.starter.pest.api.Results.LiftConfirmResult;
import com.example.starter.pest.api.Results.TraceResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处置链业务：双人登记投放（与库存扣减同事务）、监测期虫迹、恰满 14 天解除申请、双方确认解除。
 */
@Service
public class TreatmentService {

    /**
     * 投放后监测期（天）：恰满 14 天且无新增虫迹方可申请解除。
     */
    static final long MONITORING_DAYS = 14;

    private final TreatmentChainRepository chainRepository;
    private final InventoryRepository inventoryRepository;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public TreatmentService(TreatmentChainRepository chainRepository,
                            InventoryRepository inventoryRepository,
                            IdempotencyService idempotencyService, Clock clock) {
        this.chainRepository = chainRepository;
        this.inventoryRepository = inventoryRepository;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 治理投放：须双人登记药剂名称（库存批次）、用量与点位；投放记录与库存扣减在同一事务内完成；
     * 过期药剂拒绝登记；库存低于安全线自动生成采购建议并与本次（最近）投放记录勾稽。
     */
    @Transactional
    public DispenseResult dispense(long chainId, DispenseRequest request) {
        return idempotencyService.execute(request.requestId(),
                fingerprint(request.requestId(), "dispense", chainId, request.pesticideId(),
                        request.dosage(), request.points(), request.registrar1(), request.registrar2()),
                DispenseResult.class,
                () -> doDispense(chainId, request));
    }

    private DispenseResult doDispense(long chainId, DispenseRequest request) {
        Chain chain = chainRepository.findById(chainId)
                .orElseThrow(() -> ApiException.notFound("处置链不存在: " + chainId));
        if (!"TREATING".equals(chain.status()) && !"MONITORING".equals(chain.status())) {
            throw ApiException.unprocessable("当前处置链状态不允许投放: " + chain.status());
        }
        if (isBlank(request.registrar1()) || isBlank(request.registrar2())) {
            throw ApiException.unprocessable("治理投放须双人登记，单人登记不生效");
        }
        if (request.registrar1().equals(request.registrar2())) {
            throw ApiException.unprocessable("两名登记人不得为同一人");
        }
        Pesticide pesticide = inventoryRepository.findPesticide(request.pesticideId())
                .orElseThrow(() -> ApiException.notFound("药剂不存在: " + request.pesticideId()));
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        if (!today.isBefore(pesticide.expireDate())) {
            throw ApiException.unprocessable("药剂已过期，拒绝登记投放: " + pesticide.name());
        }
        if (inventoryRepository.deductStock(pesticide.id(), request.dosage()) == 0) {
            throw ApiException.unprocessable("药剂库存不足: " + pesticide.name());
        }
        Instant now = clock.instant();
        long dispenseId = inventoryRepository.insertDispense(request.requestId(), chainId,
                pesticide.id(), request.dosage(), request.points(),
                request.registrar1(), request.registrar2(), now);
        chainRepository.startMonitoring(chainId, now);

        BigDecimal remaining = pesticide.quantity().subtract(request.dosage());
        Long suggestionId = null;
        if (remaining.compareTo(pesticide.safetyLine()) < 0) {
            BigDecimal suggested = pesticide.safetyLine().multiply(BigDecimal.valueOf(2)).subtract(remaining);
            suggestionId = inventoryRepository.insertPurchaseSuggestion(pesticide.id(), suggested,
                    "库存低于安全线 " + pesticide.safetyLine().toPlainString() + " " + pesticide.unit(),
                    dispenseId, now);
        }
        return new DispenseResult(dispenseId, chainId, pesticide.id(), remaining, suggestionId);
    }

    /**
     * 监测期登记新增虫迹：监测期自虫迹发现时间重新起算。
     */
    @Transactional
    public TraceResult registerTrace(long chainId, TraceRequest request) {
        return idempotencyService.execute(request.requestId(),
                fingerprint(request.requestId(), "trace", chainId, request.recorder()),
                TraceResult.class,
                () -> {
                    Chain chain = chainRepository.findById(chainId)
                            .orElseThrow(() -> ApiException.notFound("处置链不存在: " + chainId));
                    if (!"MONITORING".equals(chain.status())) {
                        throw ApiException.unprocessable("仅监测期可登记虫迹: " + chain.status());
                    }
                    Instant now = clock.instant();
                    long traceId = chainRepository.insertTrace(chainId, now);
                    chainRepository.resetMonitoringStart(chainId, now);
                    return new TraceResult(traceId, chainId, now);
                });
    }

    /**
     * 申请解除：恰满 14 天（自最近投放或虫迹起算）且无新增虫迹方可申请。
     */
    @Transactional
    public LiftApplyResult applyLift(long chainId, LiftApplyRequest request) {
        return idempotencyService.execute(request.requestId(),
                fingerprint(request.requestId(), "lift-apply", chainId, request.applicant()),
                LiftApplyResult.class,
                () -> {
                    Chain chain = chainRepository.findById(chainId)
                            .orElseThrow(() -> ApiException.notFound("处置链不存在: " + chainId));
                    if (!"MONITORING".equals(chain.status())) {
                        throw ApiException.unprocessable("仅监测期可申请解除: " + chain.status());
                    }
                    Instant now = clock.instant();
                    Instant earliest = chain.monitoringStart().plus(MONITORING_DAYS, ChronoUnit.DAYS);
                    if (now.isBefore(earliest)) {
                        throw ApiException.unprocessable("监测期未满 14 天，不得申请解除");
                    }
                    chainRepository.markLiftRequested(chainId, now);
                    return new LiftApplyResult(chainId, "LIFT_REQUESTED", now);
                });
    }

    /**
     * 解除确认：消杀与库房双方均确认后处置链解除，库区恢复出库。
     */
    @Transactional
    public LiftConfirmResult confirmLift(long chainId, LiftConfirmRequest request) {
        return idempotencyService.execute(request.requestId(),
                fingerprint(request.requestId(), "lift-confirm", chainId, request.side(), request.confirmer()),
                LiftConfirmResult.class,
                () -> {
                    Chain chain = chainRepository.findById(chainId)
                            .orElseThrow(() -> ApiException.notFound("处置链不存在: " + chainId));
                    if (!"LIFT_REQUESTED".equals(chain.status())) {
                        throw ApiException.unprocessable("当前状态不可确认解除: " + chain.status());
                    }
                    if ("DISINFECTION".equals(request.side())) {
                        chainRepository.confirmDisinfection(chainId);
                    } else {
                        chainRepository.confirmWarehouse(chainId);
                    }
                    Chain updated = chainRepository.findById(chainId).orElseThrow();
                    if (updated.confirmDisinfection() && updated.confirmWarehouse()) {
                        chainRepository.markLifted(chainId, clock.instant());
                        chainRepository.removeActive(updated.zoneCode());
                        return new LiftConfirmResult(chainId, "LIFTED", true);
                    }
                    return new LiftConfirmResult(chainId, "LIFT_REQUESTED", false);
                });
    }

    /**
     * 查询处置链当前状态。
     */
    @Transactional(readOnly = true)
    public ChainView getChain(long chainId) {
        Chain chain = chainRepository.findById(chainId)
                .orElseThrow(() -> ApiException.notFound("处置链不存在: " + chainId));
        return new ChainView(chain.id(), chain.zoneCode(), chain.reportId(), chain.status(),
                chain.monitoringStart(), chain.liftRequestedAt(),
                chain.confirmDisinfection(), chain.confirmWarehouse(), chain.liftedAt());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
