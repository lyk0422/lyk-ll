package com.example.starter.food.service;

import com.example.starter.food.domain.ComplianceItem;
import com.example.starter.food.domain.ComplianceReport;
import com.example.starter.food.domain.EffectiveAdditive;
import com.example.starter.food.domain.RecipeData;
import com.example.starter.food.repo.AdditiveInfo;
import com.example.starter.food.repo.FoodRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 合规规则评估（可对提交数据或批准快照重复复算，结果稳定）：
 * 1. 每种添加剂按食品分类查最大使用量，终产品有效含量（直接加入 + 复合配料按占比折算带入）恰等限量合法，超限拒绝；
 * 2. 同功能添加剂并用时，各自有效用量占其限量的比例之和不得超过 1，恰等 1 合法；
 * 3. 任一单项或并用比例违例，整体判定不合规（整单拒绝）。
 */
@Component
public class ComplianceEvaluator {

    private static final int SCALE = 6;
    private static final BigDecimal ONE = BigDecimal.ONE.setScale(SCALE, RoundingMode.HALF_UP);

    private final FoodRepository repo;

    public ComplianceEvaluator(FoodRepository repo) {
        this.repo = repo;
    }

    /** 计算终产品中各添加剂的有效用量（直接量 + 带入折算量），按添加剂编码稳定排序。 */
    public List<EffectiveAdditive> effectiveAmounts(RecipeData data) {
        Map<String, BigDecimal> direct = new LinkedHashMap<>();
        for (RecipeData.DirectAdditive a : data.additives()) {
            direct.merge(a.additiveCode(), a.amount(), BigDecimal::add);
        }
        Map<String, BigDecimal> carry = new LinkedHashMap<>();
        for (RecipeData.Ingredient ing : data.ingredients()) {
            Map<String, BigDecimal> composition = repo.compoundAdditives(ing.compoundCode());
            for (Map.Entry<String, BigDecimal> e : composition.entrySet()) {
                BigDecimal brought = e.getValue().multiply(ing.proportion())
                        .setScale(SCALE, RoundingMode.HALF_UP);
                carry.merge(e.getKey(), brought, BigDecimal::add);
            }
        }
        Map<String, EffectiveAdditive> merged = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : direct.entrySet()) {
            String code = e.getKey();
            BigDecimal c = carry.getOrDefault(code, BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
            merged.put(code, new EffectiveAdditive(code, scale(e.getValue()), c,
                    scale(e.getValue().add(c))));
        }
        for (Map.Entry<String, BigDecimal> e : carry.entrySet()) {
            if (!merged.containsKey(e.getKey())) {
                BigDecimal d = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
                merged.put(e.getKey(), new EffectiveAdditive(e.getKey(), d, scale(e.getValue()),
                        scale(d.add(e.getValue()))));
            }
        }
        return merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    /** 在指定标准版本下对配方做完整合规评估。 */
    public ComplianceReport evaluate(RecipeData data, String standardVersion) {
        List<EffectiveAdditive> effective = effectiveAmounts(data);
        List<ComplianceItem> items = new ArrayList<>();
        // 功能类别 -> 该组各添加剂的（比例, 明细），用于并用比例判定
        Map<String, List<GroupMember>> groups = new LinkedHashMap<>();
        boolean allPass = true;

        for (EffectiveAdditive ea : effective) {
            AdditiveInfo info = repo.getAdditive(ea.additiveCode());
            BigDecimal max = info == null ? null
                    : repo.getLimit(ea.additiveCode(), data.categoryCode(), standardVersion);

            String name = info == null ? "未知添加剂" : info.nameCn();
            String function = info == null ? "UNKNOWN" : info.functionType();
            String source = describeSource(ea);

            if (info == null) {
                allPass = false;
                items.add(new ComplianceItem("ADDITIVE", ea.additiveCode(), name, function, source,
                        ea.directAmount(), ea.carryOverAmount(), ea.effectiveAmount(), null, null, false,
                        "添加剂目录中不存在该编码"));
                continue;
            }
            if (max == null) {
                allPass = false;
                items.add(new ComplianceItem("ADDITIVE", ea.additiveCode(), name, function, source,
                        ea.directAmount(), ea.carryOverAmount(), ea.effectiveAmount(), null, null, false,
                        "标准 " + standardVersion + " 未规定该添加剂在本食品分类的使用量，不得使用"));
                continue;
            }
            BigDecimal ratio = ea.effectiveAmount().divide(max, SCALE, RoundingMode.HALF_UP);
            boolean pass = ea.effectiveAmount().compareTo(max) <= 0;
            if (!pass) {
                allPass = false;
            }
            items.add(new ComplianceItem("ADDITIVE", ea.additiveCode(), name, function, source,
                    ea.directAmount(), ea.carryOverAmount(), ea.effectiveAmount(), scale(max), ratio, pass,
                    pass ? "有效含量 " + ea.effectiveAmount() + " ≤ 限量 " + scale(max) + " g/kg"
                            : "有效含量 " + ea.effectiveAmount() + " 超过限量 " + scale(max) + " g/kg"));
            groups.computeIfAbsent(function, k -> new ArrayList<>())
                    .add(new GroupMember(ea.additiveCode(), name, ratio, pass));
        }

        for (Map.Entry<String, List<GroupMember>> g : groups.entrySet()) {
            List<GroupMember> members = g.getValue();
            if (members.size() < 2) {
                continue;
            }
            BigDecimal sumRatio = members.stream().map(m -> m.ratio).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean pass = sumRatio.compareTo(ONE) <= 0;
            if (!pass) {
                allPass = false;
            }
            String codes = members.stream().map(m -> m.code).reduce((a, b) -> a + "+" + b).orElse("");
            items.add(new ComplianceItem("FUNCTION_GROUP", codes,
                    members.stream().map(m -> m.name).reduce((a, b) -> a + "、" + b).orElse(""),
                    g.getKey(), "同功能并用", null, null, null, null, scale(sumRatio), pass,
                    pass ? "同功能添加剂用量比例之和 " + scale(sumRatio) + " ≤ 1"
                            : "同功能添加剂用量比例之和 " + scale(sumRatio) + " 超过 1"));
        }

        return new ComplianceReport(standardVersion, items, allPass);
    }

    private static String describeSource(EffectiveAdditive ea) {
        boolean hasDirect = ea.directAmount().compareTo(BigDecimal.ZERO) > 0;
        boolean hasCarry = ea.carryOverAmount().compareTo(BigDecimal.ZERO) > 0;
        if (hasDirect && hasCarry) {
            return "直接加入+复合配料带入";
        }
        if (hasCarry) {
            return "复合配料带入";
        }
        return "直接加入";
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private record GroupMember(String code, String name, BigDecimal ratio, boolean singlePass) {
    }
}
