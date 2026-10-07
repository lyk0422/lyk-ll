package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 续行许可请求；同事务重查编组、自检有效期及最新试验版本，任一失败仍停车。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 处置单期望版本（成功加1，旧版409）
 */
public record ResumeRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion) {
}
