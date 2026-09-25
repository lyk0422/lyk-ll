package com.example.starter.forestry.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发证请求（以 requestId 幂等）。
 *
 * @param requestId     幂等键
 * @param applicationId 已审核通过的申请单 ID
 */
public record IssueRequest(@NotBlank String requestId, @NotBlank String applicationId) {
}
