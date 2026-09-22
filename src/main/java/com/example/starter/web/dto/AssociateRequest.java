package com.example.starter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 检测关联请求：既承载检测内容，也可携带显式关联目标与期望版本。
 *
 * @param targetTrackUid  显式关联轨迹，可空（空表示按门限自动关联/新建）
 * @param expectedVersion 目标轨迹期望版本，显式关联时必填用于原子 CAS
 * @param gateThreshold   本次关联门限覆盖值（米），可空使用系统默认门限
 */
public record AssociateRequest(
        @NotBlank String detectionId,
        @NotNull Long timestamp,
        @NotBlank String source,
        @NotBlank String category,
        @NotNull Double x,
        @NotNull Double y,
        @NotNull Double z,
        @NotNull @PositiveOrZero Double confidence,
        String targetTrackUid,
        Long expectedVersion,
        @PositiveOrZero Double gateThreshold) {

    /** 转换为领域检测对象。 */
    public @Valid com.example.starter.domain.Detection toDetection() {
        return new com.example.starter.domain.Detection(
                detectionId, timestamp, source, category, x, y, z, confidence);
    }
}
