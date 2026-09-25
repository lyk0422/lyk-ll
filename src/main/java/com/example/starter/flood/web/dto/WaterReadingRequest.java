package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 水位/监测读数上报请求；同一（测点, 采样时刻）重复上报去重，乱序按采样时刻归位。
 *
 * @param stationCode 监测测点编码
 * @param sampleTime 采样时刻（Asia/Shanghai）
 * @param waterLevel 实测库水位标高，米
 * @param dryBeachLength 干滩长度，米，可空（该测点未测）
 * @param phreaticDepth 浸润线埋深，米，可空（该测点未测）
 */
public record WaterReadingRequest(
        @NotBlank String stationCode,
        @NotNull LocalDateTime sampleTime,
        @NotNull BigDecimal waterLevel,
        @DecimalMin("0.0") BigDecimal dryBeachLength,
        @DecimalMin("0.0") BigDecimal phreaticDepth) {
}
