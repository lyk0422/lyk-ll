package com.example.starter.payment.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 差异核销请求。
 *
 * @param action 处理方式：WRITE_OFF 核销 / SUPPLEMENT 补记 / REFUND 退款
 */
public record DiffResolveRequest(
        @NotNull(message = "action 不能为空")
        com.example.starter.payment.domain.ResolveAction action,
        String operator,
        String remark) {
}
