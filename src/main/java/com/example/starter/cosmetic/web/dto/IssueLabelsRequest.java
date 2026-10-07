package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 标签领用请求。
 *
 * @param requestId       幂等键；指纹覆盖 requestId 与全部业务字段
 * @param batchNo         合成包装批号；首次领用登记所属产品，跨产品借用拒绝
 * @param quantity        领用张数（正整数，不得超未领余额，恰领完允许）
 * @param expectedVersion 期望的印次台账版本号（乐观并发控制，不一致 409）
 */
public record IssueLabelsRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull @Positive Integer quantity,
        @NotNull Integer expectedVersion) {
}
