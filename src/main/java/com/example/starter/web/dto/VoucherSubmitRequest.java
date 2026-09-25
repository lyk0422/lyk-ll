package com.example.starter.web.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 凭证生成请求：服务依据该站该气量日 24:00（UTC）前已确认的读数计算。
 *
 * @param requestId 幂等键
 * @param stationCode 交接站编码
 * @param gasDay 气量日（UTC）
 */
public record VoucherSubmitRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull LocalDate gasDay) {
}
