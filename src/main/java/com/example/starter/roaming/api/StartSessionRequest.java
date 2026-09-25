package com.example.starter.roaming.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * 会话启动请求。
 * 正常流程携带 sessionNo 承接已授权会话；并发直启场景可携带证书指纹、桩编号与启动时刻（UTC）。
 *
 * @param requestId       业务幂等键
 * @param sessionNo       授权返回的会话号（可空）
 * @param certFingerprint 车辆证书指纹（直启时必填）
 * @param pileNo          桩编号（直启时必填）
 * @param startTime       启动时刻（UTC，直启时必填）
 */
public record StartSessionRequest(
        @NotBlank String requestId,
        String sessionNo,
        String certFingerprint,
        String pileNo,
        Instant startTime) {
}
