package com.example.starter.cutover.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 审批请求：approved=false 时申请进入 REJECTED 终态。
 */
public record ApproveRequest(
        @NotBlank String requestUid,
        @NotBlank String requestId,
        @NotNull Boolean approved,
        String remark
) {
}
