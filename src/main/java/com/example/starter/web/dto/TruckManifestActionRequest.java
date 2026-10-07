package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 车单撤销/发车核销请求：对已存在车单按乐观版本执行，成功后版本加 1。
 *
 * @param requestId       幂等请求标识，全局唯一
 * @param expectedVersion 期望车单版本，当前版本不符返回 409；车单创建后为 1
 * @param occurredAt      业务精确时刻（UTC ISO-8601），参与指纹
 */
public record TruckManifestActionRequest(
        @NotBlank String requestId,
        @NotNull @Positive(message = "expectedVersion 须为正数（车单创建后版本为 1）")
        Integer expectedVersion,
        @NotBlank String occurredAt) {
}
