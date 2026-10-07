package com.example.starter.hazmat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 撤销接班员资格请求（乐观版本控制）。
 *
 * @param requestId      幂等键（失败不占键）
 * @param expectedVersion 期望资格版本号：与当前版本一致方可撤销，成功加 1，过期 409
 */
public record RevokeQualificationRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion) {
}
