package com.example.starter.web.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 风险等级变更请求：未完成清单提高等级原子扩充；已完成清单另开补充清单；降低一律拒绝。
 *
 * @param requestId       幂等请求标识，全局唯一
 * @param level           变更后风险等级：NORMAL 普通（10%）/ TIGHTENED 加严（30%）
 * @param ruleVersion     变更后合成风险规则版本
 * @param declaredHeadNos 申报头只集合（须与头只台账完全一致，参与指纹）
 * @param expectedVersion 期望当前风险版本，与现状不一致返回 409，成功后加 1
 * @param occurredAt      业务精确时刻（UTC ISO-8601），参与指纹
 */
public record RiskChangeRequest(
        @NotBlank String requestId,
        @NotBlank String level,
        @NotBlank String ruleVersion,
        @NotEmpty List<@NotBlank String> declaredHeadNos,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String occurredAt) {
}
