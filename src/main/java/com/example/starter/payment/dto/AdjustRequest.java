package com.example.starter.payment.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 挂账调账指令：将指定流水的挂账交易在同一事务内转入下一开放批次。
 */
public record AdjustRequest(
        @NotBlank(message = "channelSerialNo 不能为空")
        String channelSerialNo,
        String operator,
        String remark) {
}
