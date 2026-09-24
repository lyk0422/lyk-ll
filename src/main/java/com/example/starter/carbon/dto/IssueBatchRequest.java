package com.example.starter.carbon.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 签发配额/抵消信用批次请求。
 *
 * @param enterpriseId   持有企业
 * @param complianceYear 履约年度
 * @param quantity       签发量（吨CO2e）
 * @param expiryDate     到期日；扣减时到期日近者优先
 */
public record IssueBatchRequest(
        @NotNull Long enterpriseId,
        @NotNull Integer complianceYear,
        @Min(1) long quantity,
        @NotNull LocalDate expiryDate) {
}
