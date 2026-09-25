package com.example.starter.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 渠道交易入账请求。
 *
 * @param requestId       入账幂等号（非空）
 * @param channelSerialNo 渠道流水号（非空，批次内去重）
 * @param merchantNo      商户号（非空）
 * @param amount          交易金额（元，大于 0）
 * @param occurTime       交易发生时刻（UTC，ISO-8601），为空时取服务当前时间
 */
public record PaymentRequest(
        @NotBlank(message = "requestId 不能为空")
        String requestId,
        @NotBlank(message = "channelSerialNo 不能为空")
        String channelSerialNo,
        @NotBlank(message = "merchantNo 不能为空")
        String merchantNo,
        @NotNull(message = "amount 不能为空")
        @DecimalMin(value = "0.01", message = "amount 必须大于 0")
        BigDecimal amount,
        Instant occurTime) {
}
