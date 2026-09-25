package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤回请求（审核通过前可撤回，撤回为终态）。
 */
public record WithdrawRequest(
        @NotBlank(message = "operator 不能为空")
        String operator,
        String reason
) {
}
