package com.example.rebate.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 正式申报请求。
 *
 * @param reviewConfirmed {@code true} 时在同一事务内把“已说明待复核”的疑点复核确认解除；
 *     若仍有未解除疑点，本请求整体回滚（疑点与批次状态均不变）
 */
public record DeclareRequest(
        @NotBlank String requestId,
        boolean reviewConfirmed) {
}
