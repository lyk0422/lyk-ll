package com.example.starter.forestry.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 更新验收（含首次验收与补植验收）提交请求，以 requestId 幂等。
 * 验收合格率 = qualifiedCount / totalCount，不得低于 0.85。
 *
 * @param requestId      幂等键
 * @param applicationId  采伐申请单 ID
 * @param qualifiedCount 合格株数（或合格面积折算株数），单位株
 * @param totalCount     总造林株数，单位株
 */
public record AcceptanceRequest(
        @NotBlank String requestId,
        @NotBlank String applicationId,
        @NotNull @Min(0) Integer qualifiedCount,
        @NotNull @Positive Integer totalCount
) {
}
