package com.example.starter.roaming.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 即插即充授权请求：以车辆证书指纹标识车辆。
 *
 * @param requestId      业务幂等键
 * @param certFingerprint 车辆证书指纹
 * @param pileNo         桩编号
 * @param requestTime    请求时刻（UTC）
 */
public record AuthorizeRequest(
        @NotBlank String requestId,
        @NotBlank String certFingerprint,
        @NotBlank String pileNo,
        @NotNull Instant requestTime) {
}
