package com.example.starter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 温度数据上报请求（同一换热站一批单元读数）。
 *
 * @param requestId   幂等键，指纹覆盖本请求全部业务字段
 * @param stationCode 换热站编码
 * @param readings    各单元温度读数
 */
public record TemperatureReportRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotEmpty @Valid List<TemperatureReadingItem> readings
) {
}
