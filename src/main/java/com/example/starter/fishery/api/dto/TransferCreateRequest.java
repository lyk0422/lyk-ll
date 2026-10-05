package com.example.starter.fishery.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 跨船配额调剂发起请求。
 *
 * @param requestId 幂等键
 * @param fromVessel 调出渔船编号
 * @param toVessel 调入渔船编号（须与调出方不同）
 * @param speciesCode 鱼种编号
 * @param quotaYear 配额年度（UTC）
 * @param amountKg 调剂量（千克），不得超过调出船剩余额度
 */
public record TransferCreateRequest(
        @NotBlank String requestId,
        @NotBlank String fromVessel,
        @NotBlank String toVessel,
        @NotBlank String speciesCode,
        @NotNull Integer quotaYear,
        @NotNull @DecimalMin(value = "0.001", message = "调剂量必须大于0") BigDecimal amountKg) {
}
