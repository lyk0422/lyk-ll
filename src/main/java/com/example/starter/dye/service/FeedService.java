package com.example.starter.dye.service;

import com.example.starter.dye.domain.OperationType;
import com.example.starter.dye.domain.RecipeItemPO;
import com.example.starter.dye.domain.RecipePO;
import com.example.starter.dye.domain.RunStatus;
import com.example.starter.dye.domain.StockMovementPO;
import com.example.starter.dye.domain.VatRunPO;
import com.example.starter.dye.domain.WeighRecordPO;
import com.example.starter.dye.repo.DyestuffRepository;
import com.example.starter.dye.repo.RecipeRepository;
import com.example.starter.dye.repo.VatRunRepository;
import com.example.starter.dye.repo.WeighRecordRepository;
import com.example.starter.dye.web.BusinessRuleException;
import com.example.starter.dye.web.dto.FeedItem;
import com.example.starter.dye.web.dto.FeedRequest;
import com.example.starter.dye.web.dto.FeedResponse;
import com.example.starter.dye.web.dto.WeighResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投料执行服务。
 *
 * <p>放大换算：计划克重 = 小样浓度(g/L) × 浴比 N × 织物重量(kg)（浴比 1:N 即每 kg 织物 N 升液）。
 * 称量门限：|实际-计划|/计划 ≤ 0.5% 合法，恰 0.5% 合法；任一项超差整批拒绝且不扣库存。
 * 称量记录写入、库存条件扣减、缸次状态推进在同一事务内完成。</p>
 */
@Service
public class FeedService {

    /** 称量允许偏差比上限（含边界）：±0.5% */
    public static final BigDecimal DEVIATION_LIMIT = new BigDecimal("0.005");

    private static final int GRAM_SCALE = 3;
    private static final int RATIO_SCALE = 6;

    private final IdempotencyService idempotency;
    private final VatRunRepository vatRunRepository;
    private final RecipeRepository recipeRepository;
    private final DyestuffRepository dyestuffRepository;
    private final WeighRecordRepository weighRecordRepository;
    private final Clock clock;

    public FeedService(IdempotencyService idempotency, VatRunRepository vatRunRepository,
                       RecipeRepository recipeRepository, DyestuffRepository dyestuffRepository,
                       WeighRecordRepository weighRecordRepository, Clock clock) {
        this.idempotency = idempotency;
        this.vatRunRepository = vatRunRepository;
        this.recipeRepository = recipeRepository;
        this.dyestuffRepository = dyestuffRepository;
        this.weighRecordRepository = weighRecordRepository;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Outcome<FeedResponse> feed(FeedRequest request) {
        return idempotency.execute(OperationType.FEED, request.requestId(), request, () -> {
            VatRunPO run = vatRunRepository.findByNoForUpdate(request.runNo())
                    .orElseThrow(() -> new BusinessRuleException("缸次不存在: " + request.runNo()));
            if (run.getStatus() != RunStatus.PLANNED) {
                throw new BusinessRuleException("缸次 " + request.runNo() + " 当前状态 " + run.getStatus()
                        + "，仅待投料(PLANNED)缸次可投料");
            }
            RecipePO recipe = recipeRepository.find(run.getRecipeCode(), run.getRecipeVersion())
                    .orElseThrow(() -> new BusinessRuleException("缸次引用的配方版本缺失"));
            List<RecipeItemPO> items = recipeRepository.findItems(recipe.getId());

            Map<String, BigDecimal> actualByCode = new LinkedHashMap<>();
            for (FeedItem item : request.items()) {
                if (actualByCode.put(item.dyestuffCode(), item.actualGrams()) != null) {
                    throw new BusinessRuleException("投料明细染化料重复: " + item.dyestuffCode());
                }
            }
            Set<String> plannedCodes = new HashSet<>(items.stream().map(RecipeItemPO::getDyestuffCode).toList());
            if (!plannedCodes.equals(actualByCode.keySet())) {
                throw new BusinessRuleException("投料明细必须与配方染化料逐一对应（不多不少）");
            }

            LocalDateTime now = LocalDateTime.now(clock);
            List<WeighResult> results = new ArrayList<>();
            boolean allWithinTolerance = true;
            for (RecipeItemPO item : items) {
                BigDecimal planned = plannedGrams(item.getGramsPerLiter(), run.getLiquorRatio(),
                        run.getFabricKg());
                BigDecimal actual = actualByCode.get(item.getDyestuffCode());
                BigDecimal deviation = deviationRatio(planned, actual);
                boolean accepted = deviation.compareTo(DEVIATION_LIMIT) <= 0;
                allWithinTolerance &= accepted;
                results.add(new WeighResult(item.getDyestuffCode(), planned, actual, deviation, accepted));
                weighRecordRepository.insert(buildWeigh(run.getId(), request.requestId(),
                        item.getDyestuffCode(), planned, actual, deviation, accepted), now);
            }

            if (!allWithinTolerance) {
                // 整批拒绝：称量链留痕，库存无任何扣减，缸次维持 PLANNED
                FeedResponse body = new FeedResponse(request.requestId(), request.runNo(), false,
                        "称量偏差超过 ±0.5%，拒绝投料，库存未扣减", results);
                return IdempotencyService.ServiceResult.of(422, body);
            }

            // 库存充足性检查（不足同样整批拒绝、不扣减）
            for (WeighResult r : results) {
                BigDecimal stock = dyestuffRepository.getStock(r.dyestuffCode());
                if (stock.compareTo(r.actualGrams()) < 0) {
                    FeedResponse body = new FeedResponse(request.requestId(), request.runNo(), false,
                            "染化料 " + r.dyestuffCode() + " 库存不足（余额 " + stock + "g，需 "
                                    + r.actualGrams() + "g），拒绝投料，库存未扣减",
                            results);
                    return IdempotencyService.ServiceResult.of(422, body);
                }
            }

            // 条件原子扣减 + 台账（与称量同一事务，要么全成要么全回滚）
            for (WeighResult r : results) {
                if (!dyestuffRepository.deductStock(r.dyestuffCode(), r.actualGrams())) {
                    throw new BusinessRuleException("染化料 " + r.dyestuffCode()
                            + " 库存并发扣减失败，整批回滚");
                }
                BigDecimal balance = dyestuffRepository.getStock(r.dyestuffCode());
                StockMovementPO movement = new StockMovementPO();
                movement.setRequestId(request.requestId());
                movement.setDyestuffCode(r.dyestuffCode());
                movement.setChangeGrams(r.actualGrams().negate());
                movement.setBalanceGrams(balance);
                movement.setRunNo(run.getRunNo());
                movement.setReason("CONSUME");
                dyestuffRepository.insertMovement(movement, now);
            }
            vatRunRepository.updateStatus(run.getId(), RunStatus.RUNNING);

            FeedResponse body = new FeedResponse(request.requestId(), request.runNo(), true,
                    "称量合格，投料成功，库存已扣减", results);
            return IdempotencyService.ServiceResult.ok(body);
        });
    }

    /**
     * 浴比放大：浓度(g/L) × 浴比 N(L/kg) × 织物(kg) = 克重，保留 3 位小数。
     */
    public static BigDecimal plannedGrams(BigDecimal gramsPerLiter, int liquorRatio, BigDecimal fabricKg) {
        return gramsPerLiter.multiply(BigDecimal.valueOf(liquorRatio)).multiply(fabricKg)
                .setScale(GRAM_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 偏差比 |实际-计划|/计划，保留 6 位小数。
     */
    public static BigDecimal deviationRatio(BigDecimal planned, BigDecimal actual) {
        BigDecimal diff = actual.subtract(planned).abs();
        return diff.divide(planned, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private WeighRecordPO buildWeigh(long runId, String requestId, String code, BigDecimal planned,
                                     BigDecimal actual, BigDecimal deviation, boolean accepted) {
        WeighRecordPO po = new WeighRecordPO();
        po.setRunId(runId);
        po.setRequestId(requestId);
        po.setDyestuffCode(code);
        po.setPlannedGrams(planned);
        po.setActualGrams(actual);
        po.setDeviationRatio(deviation);
        po.setAccepted(accepted);
        return po;
    }
}
