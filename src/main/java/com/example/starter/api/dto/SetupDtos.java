package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合成基础数据维护：作物生育期窗口与分区。仅用于本地演示与测试造数。
 */
public final class SetupDtos {

    private SetupDtos() {
    }

    public record StageWindow(
            @NotBlank @Size(max = 64) String stageCode,
            @NotBlank String stageName,
            @NotNull BigDecimal dayTempMin,
            @NotNull BigDecimal dayTempMax,
            @NotNull BigDecimal nightTempMin,
            @NotNull BigDecimal nightTempMax,
            @NotNull BigDecimal humidityMin,
            @NotNull BigDecimal humidityMax) {
    }

    public record CropSetupRequest(
            @NotBlank @Size(max = 64) String cropCode,
            @NotBlank String cropName,
            @NotEmpty @Valid List<StageWindow> stages) {
    }

    public record ZoneSetupRequest(
            @NotBlank @Size(max = 64) String zoneCode,
            @NotBlank String zoneName) {
    }

    public record ZoneResponse(String zoneCode, String zoneName) {
    }
}
