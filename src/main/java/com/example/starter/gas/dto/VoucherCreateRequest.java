package com.example.starter.gas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 交接凭证生成请求：以气量日 24:00（UTC）前已确认入库的读数为准。
 */
public record VoucherCreateRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotNull LocalDate gasDate) {
}
