package com.example.starter.web.dto;

import com.example.starter.domain.Detection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 检测内容体（可嵌套于 ID 切换等请求中复用）。
 */
public record DetectionBody(
        @NotBlank String detectionId,
        @NotNull Long timestamp,
        @NotBlank String source,
        @NotBlank String category,
        @NotNull Double x,
        @NotNull Double y,
        @NotNull Double z,
        @NotNull @PositiveOrZero Double confidence) {

    public Detection toDetection() {
        return new Detection(detectionId, timestamp, source, category, x, y, z, confidence);
    }
}
