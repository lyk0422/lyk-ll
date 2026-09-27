package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 抽检结果逐只提交请求：NEGATIVE 阴性 / POSITIVE 阳性。
 *
 * @param requestId       幂等请求标识，全局唯一
 * @param headNo          头只编号，须属于当前清单冻结范围，清单外拒绝
 * @param result          抽检结果：NEGATIVE 阴性 / POSITIVE 阳性
 * @param expectedVersion 期望当前风险版本，与现状不一致返回 409，成功接受新结果后加 1
 * @param occurredAt      业务精确时刻（UTC ISO-8601），参与指纹
 */
public record RiskResultRequest(
        @NotBlank String requestId,
        @NotBlank String headNo,
        @NotBlank String result,
        @NotNull @Positive Integer expectedVersion,
        @NotBlank String occurredAt) {
}
