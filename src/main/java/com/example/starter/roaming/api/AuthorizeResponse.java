package com.example.starter.roaming.api;

import java.time.Instant;

/**
 * 授权结果；5 分钟有效期内重复请求返回首次授权结果。
 *
 * @param sessionNo       会话号
 * @param certFingerprint 车辆证书指纹
 * @param pileNo          桩编号
 * @param status          会话状态
 * @param startTime       授权时刻（UTC）
 * @param expiresAt       授权有效期截止时刻（UTC，首次授权 +5 分钟）
 * @param replayed        是否为有效期内重复授权（返回首次结果）
 */
public record AuthorizeResponse(
        String sessionNo,
        String certFingerprint,
        String pileNo,
        String status,
        Instant startTime,
        Instant expiresAt,
        boolean replayed) {
}
