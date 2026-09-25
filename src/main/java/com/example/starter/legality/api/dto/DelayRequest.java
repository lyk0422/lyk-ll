package com.example.starter.legality.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 延误延长上报。
 *
 * @param requestId     本次延误上报的幂等键
 * @param dutyRequestId 目标值勤记录提交时使用的 requestId
 * @param extensionMinutes 本次延长分钟数（正数）
 */
public record DelayRequest(
        @NotBlank(message = "requestId 不能为空")
        String requestId,
        @NotBlank(message = "dutyRequestId 不能为空")
        String dutyRequestId,
        @NotNull(message = "extensionMinutes 不能为空")
        @Min(value = 1, message = "extensionMinutes 必须为正数")
        Integer extensionMinutes,
        String note
) {
}
