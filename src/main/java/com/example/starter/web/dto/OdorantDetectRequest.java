package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 末端加臭浓度检测上报请求。
 *
 * @param stationCode       门站编码
 * @param requestId         幂等键
 * @param detectedAt        检测时刻（业务时间）
 * @param gasFlowM3         检测时段标况累计流量，单位 m³
 * @param concentrationMgM3 末端加臭剂实测浓度，单位 mg/m³
 */
public record OdorantDetectRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull LocalDateTime detectedAt,
        @NotNull @DecimalMin("0") BigDecimal gasFlowM3,
        @NotNull @DecimalMin("0") BigDecimal concentrationMgM3) {
}
