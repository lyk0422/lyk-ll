package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 换辊实绩回报请求，按“轧辊号＋实绩时刻”去重。
 *
 * @param requestId  幂等键，指纹覆盖 requestId 与全部业务字段
 * @param rollNo     轧辊号；允许为空，空轧辊号由业务层拒绝并提示补录，拒绝记录不入库
 * @param rollingKm  本次实绩里程（公里）
 * @param recordedAt 实绩发生时刻
 */
public record RollActualRequest(
        @NotBlank String requestId,
        String rollNo,
        @NotNull @Positive BigDecimal rollingKm,
        @NotNull LocalDateTime recordedAt) {
}
