package com.example.starter.cosmetic.child.domain;

import java.util.List;

/**
 * 分龄规则版本（合成初始化，受理时随修订冻结）。
 *
 * @param ruleVersion 规则版本号
 * @param bands       档位列表（0~36月、37~144月）
 */
public record ChildAgeRule(String ruleVersion, List<AgeBand> bands) {

    /** 申请年龄范围 [lower, upper] 触及的全部档位（跨档返回两档）。 */
    public List<AgeBand> requiredBands(int lower, int upper) {
        return bands.stream().filter(b -> b.intersects(lower, upper)).toList();
    }
}
