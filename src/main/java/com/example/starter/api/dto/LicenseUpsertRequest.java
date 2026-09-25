package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.math.BigDecimal;

/**
 * 合成危废经营许可录入请求（仅用于本地合成许可库维护）。
 *
 * @param licenseNo  许可证编号
 * @param holderName  持证单位
 * @param validFrom   生效日期（含当日）
 * @param validTo     截止日期（含当日），申报审核时刻晚于该日期即过期拒绝
 * @param revoked     是否被吊销
 */
public record LicenseUpsertRequest(
        @NotBlank String licenseNo,
        @NotBlank String holderName,
        @NotNull LocalDate validFrom,
        @NotNull LocalDate validTo,
        Boolean revoked) {
}
