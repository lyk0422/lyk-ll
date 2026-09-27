package com.example.starter.cosmetic.child.web.dto;

import com.example.starter.cosmetic.child.domain.AgeBand;
import com.example.starter.cosmetic.child.domain.ChildEfficacy;
import com.example.starter.cosmetic.child.domain.ChildRestrictedLimit;

import java.util.List;

/**
 * 分龄规则查询响应（合成初始化内容）。
 *
 * @param ruleVersion       当前启用的分龄规则版本
 * @param bands             档位定义（0~36月、37~144月，整数月闭区间）
 * @param restrictedLimits  儿童限用成分分档限值（跨档按较严者判定，恰等合格）
 * @param childEfficacies   儿童功效合成目录
 */
public record AgeRuleResponse(
        String ruleVersion,
        List<AgeBand> bands,
        List<ChildRestrictedLimit> restrictedLimits,
        List<ChildEfficacy> childEfficacies) {
}
