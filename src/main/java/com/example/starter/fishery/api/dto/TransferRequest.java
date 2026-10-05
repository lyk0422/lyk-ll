package com.example.starter.fishery.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 跨船配额调剂发起请求。
 *
 * @param requestId 幂等键（指纹覆盖 requestId 与全部业务字段）
 * @param fromVesselCode 调出渔船编号（发起方，提交即视为发起方确认）
 * @param toVesselCode 调入渔船编号（对方确认后生效）
 * @param speciesCode 鱼种编号
 * @param quotaYear 配额年度（UTC）
 * @param amountKg 调剂量（千克），恰等调出船剩余额度合法，超出整笔拒绝
 */
public record TransferRequest(
        @NotBlank String requestId,
        @NotBlank String fromVesselCode,
        @NotBlank String toVesselCode,
        @NotBlank String speciesCode,
        @NotNull Integer quotaYear,
        @NotNull @DecimalMin(value = "0.001", message = "调剂量必须大于0") BigDecimal amountKg) {
}
