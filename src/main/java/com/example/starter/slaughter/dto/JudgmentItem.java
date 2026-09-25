package com.example.starter.slaughter.dto;

import com.example.starter.slaughter.domain.JudgmentCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单头判定分流项。
 *
 * @param headNo        头只编号
 * @param category      判定类别 QUALIFIED/CONDITIONAL/RENDERED/DESTROYED
 * @param limitedRegion CONDITIONAL 时必填的限定流通区域；其他类别必须为空
 */
public record JudgmentItem(
        @NotBlank String headNo,
        @NotNull JudgmentCategory category,
        String limitedRegion) {
}
