package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤回请求：仅审核通过前可撤回，撤回为终态。
 *
 * @param operator 操作人
 * @param reason   撤回原因
 */
public record WithdrawRequest(@NotBlank String operator, String reason) {
}
