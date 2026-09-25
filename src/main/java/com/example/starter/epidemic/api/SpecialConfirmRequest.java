package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 特殊订正确认请求；同一审核员重复确认视为重放，不增加有效确认人数。
 */
public record SpecialConfirmRequest(
        @NotBlank(message = "reviewer 不能为空")
        String reviewer,
        String opinion
) {
}
