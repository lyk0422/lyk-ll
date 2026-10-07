package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 撤销接班员资格请求（乐观锁修改）。
 *
 * @param requestId       幂等键
 * @param expectedVersion 期望的当前资格版本号；不一致返回 409
 */
public record QualificationRevokeRequest(
        @NotBlank String requestId,
        @Positive int expectedVersion) {
}
