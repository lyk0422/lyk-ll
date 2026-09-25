package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 管网压力检测上报请求。
 *
 * @param stationCode 门站编码
 * @param requestId   幂等键
 * @param detectedAt  检测时刻（业务时间）
 * @param pressureMpa 实测管网压力，单位 MPa
 * @param gasFlowM3   联动时段标况流量，单位 m³，用于按比例核算加注量
 */
public record PressureDetectRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull LocalDateTime detectedAt,
        @NotNull @DecimalMin("0") BigDecimal pressureMpa,
        @NotNull @DecimalMin("0") BigDecimal gasFlowM3) {
}
