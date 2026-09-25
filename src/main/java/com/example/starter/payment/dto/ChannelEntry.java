package com.example.starter.payment.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 渠道对账文件中的一笔流水（合成数据）。
 */
public record ChannelEntry(
        @NotBlank(message = "channelSerialNo 不能为空")
        String channelSerialNo,
        @NotBlank(message = "merchantNo 不能为空")
        String merchantNo,
        @NotNull(message = "amount 不能为空")
        @DecimalMin(value = "0.01", message = "amount 必须大于 0")
        BigDecimal amount) {
}
