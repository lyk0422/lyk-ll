package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.AgeBandRule;

import java.util.List;

/**
 * 儿童分龄规则响应（合成初始化结果）。
 *
 * @param ruleVersion 当前分龄规则版本号
 * @param bands       分龄档定义（整数月闭区间，按下限升序）
 */
public record AgeRuleResponse(String ruleVersion, List<BandView> bands) {

    /** 分龄档视图。 */
    public record BandView(String bandCode, int lowerMonths, int upperMonths) {

        static BandView from(AgeBandRule r) {
            return new BandView(r.bandCode(), r.lowerMonths(), r.upperMonths());
        }
    }

    /** 由领域对象构造。 */
    public static AgeRuleResponse of(String ruleVersion, List<AgeBandRule> bands) {
        return new AgeRuleResponse(ruleVersion, bands.stream().map(BandView::from).toList());
    }
}
