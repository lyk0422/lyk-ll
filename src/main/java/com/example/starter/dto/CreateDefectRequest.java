package com.example.starter.dto;

import com.example.starter.model.DefectLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 缺陷登记请求。detectedAt 为空时取当前时刻(UTC)。
 */
public record CreateDefectRequest(
        @NotBlank String requestId,
        @NotNull Long bladeId,
        @NotBlank String bladePosition,
        @NotNull DefectLevel defectLevel,
        @NotBlank String defectType,
        LocalDateTime detectedAt) {
}
