package com.example.starter.feed.service;

import com.example.starter.feed.domain.FormulaItem;
import com.example.starter.feed.domain.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配方合规校验（纯逻辑，可独立复算）：
 * <ol>
 *   <li>组成项必须存在于合成目录；</li>
 *   <li>药物添加剂添加比例不得超过目录最大比例，恰等于上限合法；</li>
 *   <li>命中配伍禁忌清单的药物组合直接拒绝；</li>
 *   <li>含药配方必须绑定非空目标动物种类。</li>
 * </ol>
 */
public final class ComplianceValidator {

    private ComplianceValidator() {
    }

    /**
     * 校验结果。
     *
     * @param passed            是否全部通过
     * @param reasons           拒绝原因（通过时为空）
     * @param medicated         是否含药物添加剂
     * @param maxWithdrawalDays 含药版本各药物中的最大休药天数（非含药为 0）
     */
    public record Result(boolean passed, List<String> reasons, boolean medicated, int maxWithdrawalDays) {
    }

    /** 构造无向禁忌对键（字典序，与仓储存储口径一致）。 */
    public static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /**
     * 执行合规校验。
     *
     * @param items          配方组成项
     * @param catalog        编码 → 目录项（由仓储装配）
     * @param contraPairs    禁忌对键集合（{@link #pairKey} 口径，由禁忌清单表加载）
     * @param targetSpecies  目标动物种类
     */
    public static Result validate(List<FormulaItem> items, Map<String, Ingredient> catalog,
                                  Set<String> contraPairs, String targetSpecies) {
        List<String> reasons = new ArrayList<>();
        boolean medicated = false;
        int maxWithdrawalDays = 0;
        List<Ingredient> usedDrugs = new ArrayList<>();

        for (FormulaItem item : items) {
            Ingredient ingredient = catalog.get(item.ingredientCode());
            if (ingredient == null) {
                reasons.add("组成项 " + item.ingredientCode() + " 不在原料/药物添加剂目录中");
                continue;
            }
            if (ingredient.medicated()) {
                medicated = true;
                usedDrugs.add(ingredient);
                if (ingredient.withdrawalDays() != null && ingredient.withdrawalDays() > maxWithdrawalDays) {
                    maxWithdrawalDays = ingredient.withdrawalDays();
                }
                if (ingredient.maxRatio() != null && item.ratio().compareTo(ingredient.maxRatio()) > 0) {
                    reasons.add("药物添加剂 " + ingredient.code() + " 添加比例 " + item.ratio()
                            + " 超过最大允许比例 " + ingredient.maxRatio());
                }
            }
        }

        // 配伍禁忌：无向两两组合，同一对只报一次
        Set<String> reported = new LinkedHashSet<>();
        for (int i = 0; i < usedDrugs.size(); i++) {
            for (int j = i + 1; j < usedDrugs.size(); j++) {
                Ingredient a = usedDrugs.get(i);
                Ingredient b = usedDrugs.get(j);
                if (a.code().equals(b.code())) {
                    continue;
                }
                String key = pairKey(a.code(), b.code());
                if (contraPairs.contains(key) && reported.add(key)) {
                    reasons.add("药物组合命中配伍禁忌：" + key.replace('|', ' '));
                }
            }
        }

        if (medicated && (targetSpecies == null || targetSpecies.isBlank())) {
            reasons.add("含药配方必须绑定目标动物种类");
        }

        return new Result(reasons.isEmpty(), List.copyOf(reasons), medicated, maxWithdrawalDays);
    }
}
