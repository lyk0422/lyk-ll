package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 日指定气量（能量）登记请求。
 *
 * @param stationCode 交接站编码
 * @param gasDay 气量日（UTC）
 * @param nominatedEnergyMj 日指定能量，单位 MJ
 * @param penaltyUnitPrice 超出免考区间的违约金单价，单位 元/MJ
 */
public record NominationRequest(
        @NotBlank String stationCode,
        @NotNull LocalDate gasDay,
        @NotNull @DecimalMin("0.001") BigDecimal nominatedEnergyMj,
        @NotNull @DecimalMin("0.0") BigDecimal penaltyUnitPrice) {
}
