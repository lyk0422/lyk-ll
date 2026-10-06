package com.example.starter.transition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 失控暂停后复核确认恢复请求。
 *
 * @param requestId       幂等键
 * @param reviewedBy      复核人
 * @param reviewConfirmed 复核确认标记，须为 true 才允许恢复
 */
public record ResumeExecutionRequest(
        @NotBlank String requestId,
        @NotBlank String reviewedBy,
        @NotNull Boolean reviewConfirmed) {
}
