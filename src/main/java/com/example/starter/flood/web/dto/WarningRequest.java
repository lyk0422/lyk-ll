package com.example.starter.flood.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 降雨预警/险情处置链创建请求。
 *
 * @param requestId 幂等键，同键同参返回首次结果，同键异参返回 409
 * @param pondCode 尾矿库编码
 * @param title 预警标题
 * @param rainSummary 雨情摘要（可选）
 */
public record WarningRequest(
        @NotBlank String requestId,
        @NotBlank String pondCode,
        @NotBlank String title,
        String rainSummary) {
}
