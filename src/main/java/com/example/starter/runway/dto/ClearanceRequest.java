package com.example.starter.runway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 清场凭据提交请求：同一施工段须两个不同验收人各提交一份后段完成。
 *
 * @param requestId     幂等请求标识
 * @param acceptor      验收人标识（合成）
 * @param credentialRef 清场凭据编号（合成）
 * @param submittedAt   凭据提交时刻（UTC）
 */
public record ClearanceRequest(
        @NotBlank String requestId,
        @NotBlank String acceptor,
        @NotBlank String credentialRef,
        @NotNull Instant submittedAt) {
}
