package com.example.starter.compliance.engine;

import com.example.starter.compliance.domain.DirectAdditive;
import com.example.starter.compliance.domain.IngredientUse;
import com.example.starter.compliance.repo.AdditiveRow;
import com.example.starter.compliance.repo.IngredientAdditiveRow;
import com.example.starter.compliance.repo.LimitRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

import com.example.starter.compliance.engine.ReviewModels.GroupResult;
import com.example.starter.compliance.engine.ReviewModels.ItemResult;
import com.example.starter.compliance.engine.ReviewModels.ReviewReport;

/**
 * 限量判定引擎（纯计算，无数据库与时钟依赖，可对批准快照/审查报告独立复算）：
 * <ol>
 *   <li>逐项限量：终产品中某添加剂总含量 = 直接添加量 + Σ(配料占比 × 配料中本底含量)，
 *       总含量 ≤ 最大使用量合法，恰等限量合法；该分类无限量标准视为不允许使用，判定违例。</li>
 *   <li>同功能并用：按功能类别归组，组内各添加剂“总含量/限量”比例之和 ≤ 1 合法，恰等 1 合法。</li>
 * </ol>
 * 全部数值比较使用 BigDecimal#compareTo（与标度无关），输出明细统一保留 6 位小数。
 */
@Component
public class LimitEngine {

    private static final int SCALE = 6;

    public ReviewReport evaluate(ReviewInput input, long evaluatedAt) {
        Map<String, BigDecimal> directByCode = sumByAdditive(input.directAdditives());
        Map<String, BigDecimal> carriedByCode = carryOver(input.ingredientUses(),
                input.ingredientAdditives());

        // 出现在终产品中的全部添加剂（直接或带入），按编码排序保证明细稳定可复算。
        TreeMap<String, BigDecimal[]> totals = new TreeMap<>();
        merge(totals, directByCode, 0);
        merge(totals, carriedByCode, 1);

        List<ItemResult> items = new ArrayList<>();
        // 功能组：functionCode -> 组内各添加剂用量比例（仅对有限量的添加剂累计）
        Map<String, List<BigDecimal>> ratiosByFunction = new TreeMap<>();
        Map<String, List<String>> codesByFunction = new TreeMap<>();

        for (Map.Entry<String, BigDecimal[]> entry : totals.entrySet()) {
            String code = entry.getKey();
            BigDecimal direct = nz(entry.getValue()[0]);
            BigDecimal carried = nz(entry.getValue()[1]);
            BigDecimal total = direct.add(carried).setScale(SCALE, RoundingMode.HALF_UP);

            AdditiveRow additive = input.additiveCatalog().get(code);
            LimitRow limit = input.limits().get(code);

            BigDecimal limitAmount = null;
            BigDecimal usageRatio = null;
            boolean passed;
            String reason;
            if (additive == null) {
                passed = false;
                reason = "ADDITIVE_NOT_IN_CATALOG";
            } else if (limit == null) {
                passed = false;
                reason = "LIMIT_NOT_DEFINED";
            } else {
                limitAmount = limit.maxAmount().setScale(SCALE, RoundingMode.HALF_UP);
                usageRatio = ratio(total, limit.maxAmount());
                passed = total.compareTo(limit.maxAmount()) <= 0;
                reason = passed ? "OK" : "OVER_LIMIT";
                ratiosByFunction.computeIfAbsent(additive.functionCode(), k -> new ArrayList<>())
                        .add(usageRatio);
                codesByFunction.computeIfAbsent(additive.functionCode(), k -> new ArrayList<>())
                        .add(code);
            }

            items.add(new ItemResult(code,
                    additive == null ? null : additive.functionCode(),
                    direct.setScale(SCALE, RoundingMode.HALF_UP),
                    carried.setScale(SCALE, RoundingMode.HALF_UP),
                    total, limitAmount, usageRatio, passed, reason));
        }

        List<GroupResult> groups = new ArrayList<>();
        boolean groupsPassed = true;
        for (Map.Entry<String, List<BigDecimal>> entry : ratiosByFunction.entrySet()) {
            BigDecimal combined = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
            for (BigDecimal ratio : entry.getValue()) {
                combined = combined.add(ratio);
            }
            boolean groupPassed = combined.compareTo(BigDecimal.ONE) <= 0;
            groupsPassed &= groupPassed;
            groups.add(new GroupResult(entry.getKey(),
                    codesByFunction.get(entry.getKey()).stream().sorted().toList(),
                    combined, groupPassed));
        }

        boolean passed = items.stream().allMatch(ItemResult::passed) && groupsPassed;
        return new ReviewReport(passed, input.categoryCode(),
                List.copyOf(items), List.copyOf(groups), evaluatedAt);
    }

    /** 复合配料带入折算：终产品中带入量 = Σ 配料占比 × 配料中该添加剂本底含量（g/kg）。 */
    private Map<String, BigDecimal> carryOver(List<IngredientUse> uses,
            Map<String, List<IngredientAdditiveRow>> ingredientAdditives) {
        Map<String, BigDecimal> carried = new LinkedHashMap<>();
        for (IngredientUse use : uses) {
            List<IngredientAdditiveRow> rows =
                    ingredientAdditives.getOrDefault(use.ingredientCode(), List.of());
            for (IngredientAdditiveRow row : rows) {
                BigDecimal contribution = use.ratio().multiply(row.concentration());
                carried.merge(row.additiveCode(), contribution, BigDecimal::add);
            }
        }
        return carried;
    }

    private Map<String, BigDecimal> sumByAdditive(List<DirectAdditive> additives) {
        Map<String, BigDecimal> summed = new LinkedHashMap<>();
        for (DirectAdditive additive : additives) {
            summed.merge(additive.additiveCode(), additive.amount(), BigDecimal::add);
        }
        return summed;
    }

    /** 将直接用量（下标 0）与带入量（下标 1）合并到同一有序容器。 */
    private void merge(TreeMap<String, BigDecimal[]> target, Map<String, BigDecimal> source,
            int index) {
        for (Map.Entry<String, BigDecimal> entry : source.entrySet()) {
            BigDecimal[] pair = target.computeIfAbsent(entry.getKey(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            pair[index] = nz(pair[index]).add(entry.getValue());
        }
    }

    private BigDecimal ratio(BigDecimal amount, BigDecimal limit) {
        return amount.divide(limit, SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
