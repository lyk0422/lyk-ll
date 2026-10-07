package com.example.starter.cosmetic.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 未领标签销毁登记请求。
 *
 * @param requestId       幂等键；指纹覆盖 requestId 与全部业务字段
 * @param quantity        销毁张数（正整数，仅未领部分可销毁）
 * @param expectedVersion 期望的印次台账版本号（乐观并发控制，不一致 409）
 * @param reason          销毁原因（留证）
 */
public record DestroyLabelsRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer quantity,
        @NotNull Integer expectedVersion,
        String reason) {
}
