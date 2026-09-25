package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日结（消耗勾稽与封存）请求。
 *
 * @param stationCode        门站编码
 * @param requestId          幂等键
 * @param bizDate            业务日期（Asia/Shanghai）
 * @param reportedOdorantMg 装置侧实际上报加臭剂消耗，单位 mg
 */
public record DailyCloseRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull LocalDate bizDate,
        @NotNull @DecimalMin("0") BigDecimal reportedOdorantMg) {
}
