package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐配置调整请求：携带 expectedVersion 乐观锁，成功变更版本加 1。
 *
 * @param stationCode     门站编码
 * @param requestId       幂等键
 * @param expectedVersion 期望的当前版本号，不一致返回 409
 * @param capacityKg      新容量 kg（须大于安全储备且不小于当前余额）
 * @param safetyReserveKg 新安全储备 kg（0 ≤ 安全储备 < 容量）
 * @param adjustedAt      调整精确时刻（业务时间，Asia/Shanghai）
 */
public record TankAdjustRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal capacityKg,
        @NotNull @DecimalMin("0") BigDecimal safetyReserveKg,
        @NotNull LocalDateTime adjustedAt) {
}
