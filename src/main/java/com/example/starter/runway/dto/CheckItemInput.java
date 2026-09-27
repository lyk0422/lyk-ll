package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 单个例行检查项输入。合法文本可含任意分隔符，指纹采用结构化编码，无歧义。
 *
 * @param itemCode    检查项编码
 * @param itemText    检查项文本（可含分隔符）
 * @param passed      该项是否通过
 * @param completedAt 该项完成精确时刻（UTC）
 */
public record CheckItemInput(
        @NotBlank String itemCode,
        @NotNull String itemText,
        @NotNull Boolean passed,
        @NotNull Instant completedAt) {
}
