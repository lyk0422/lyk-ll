package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.child.domain.AgeTier;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 分龄合成规则视图（受理冻结依据；含版本、分档与各档成分限值）。
 *
 * @param ruleVersion 当前分龄规则版本
 * @param tiers       分档定义（年龄闭区间，整数月）
 * @param limits      各分档成分限值（键为分档编码，值为 成分编码→最高浓度%）
 */
public record ChildRuleResponse(String ruleVersion,
                                List<TierView> tiers,
                                Map<String, Map<String, BigDecimal>> limits) {

    /** 分档视图。 */
    public record TierView(String tierCode, int ageFromMonths, int ageToMonths) {
        public static TierView from(AgeTier tier) {
            return new TierView(tier.name(), tier.fromMonths(), tier.toMonths());
        }
    }
}
