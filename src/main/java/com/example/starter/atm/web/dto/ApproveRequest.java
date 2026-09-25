package com.example.starter.atm.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 审批请求：requestId 幂等。审批通过时刻起 4 小时为押运时限。 */
public record ApproveRequest(@NotBlank String requestId) {
}
