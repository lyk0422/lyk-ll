package com.example.starter.carbon.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 清缴申请：配额与抵消信用组合，按 requestId 幂等。
 *
 * @param requestId       幂等键
 * @param enterpriseId    申请企业
 * @param complianceYear  履约年度
 * @param allowanceAmount 本次清缴配额量（吨CO2e）
 * @param offsetAmount    本次核销抵消信用量（吨CO2e），不得超过核定排放量的 5%
 */
public record SubmitSurrenderRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long enterpriseId,
        @NotNull Integer complianceYear,
        @Min(0) long allowanceAmount,
        @Min(0) long offsetAmount) {
}
