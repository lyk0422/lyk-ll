package com.example.starter.roaming.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 授权（暨会话启动）响应。
 *
 * @param authorized        是否授权成功
 * @param cached            是否为 5 分钟有效期内重复请求回放的首次授权结果
 * @param expiresAt         授权有效期截止（authAt + 5 分钟，UTC）
 */
public record AuthResponse(
        String requestId,
        boolean authorized,
        boolean cached,
        String sessionNo,
        String certFingerprint,
        String pointId,
        String homeOperator,
        String chargingOperator,
        Instant authAt,
        Instant expiresAt,
        String status) {
}
