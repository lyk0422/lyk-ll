package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 作业申请请求。
 *
 * @param requestId       申请/潜次业务键，同时是幂等键
 * @param diverACode      组员一工号
 * @param diverBCode      组员二工号
 * @param plannedDepthM   计划深度，米
 * @param plannedBottomMin 计划水下时间，分钟
 */
public record ApplyRequest(
        @NotBlank String requestId,
        @NotBlank String diverACode,
        @NotBlank String diverBCode,
        @Positive int plannedDepthM,
        @Positive int plannedBottomMin) {
}
