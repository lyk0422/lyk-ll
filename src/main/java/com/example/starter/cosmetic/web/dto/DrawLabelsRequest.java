package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 标签领用请求。
 *
 * <p>领用绑定同产品的包装批号（首次使用登记所属产品，跨产品借用拒绝），
 * 不能超未领余额，恰领完允许；领用前重查当前有效备案，旧修订印次不得再领。</p>
 *
 * @param requestId       幂等键；指纹覆盖 requestId 与全部业务字段（含时刻）
 * @param batchNo         包装批号（合成）
 * @param quantity        本次领用张数（正整数）
 * @param expectedVersion 印次台账期望版本（乐观并发控制，旧版 409）
 * @param atUtc           业务时刻(UTC)，可空（空取当前时刻）；参与幂等指纹
 */
public record DrawLabelsRequest(
        @NotBlank String requestId,
        @NotBlank String batchNo,
        @NotNull @Min(1) Integer quantity,
        @NotNull @Min(1) Integer expectedVersion,
        Instant atUtc) {
}
