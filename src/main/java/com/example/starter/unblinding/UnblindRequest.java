package com.example.starter.unblinding;

import jakarta.validation.constraints.NotBlank;

/**
 * 应急揭盲请求：须两名不同授权人在同一事务内先后确认方可生效。
 */
public record UnblindRequest(
        @NotBlank(message = "screeningNo 不能为空") String screeningNo,
        @NotBlank(message = "approver1 不能为空") String approver1,
        @NotBlank(message = "approver2 不能为空") String approver2,
        @NotBlank(message = "reason 不能为空") String reason) {
}
