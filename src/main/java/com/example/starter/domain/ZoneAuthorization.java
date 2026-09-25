package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 重叠区作业授权。冲突判定按半开区间 [startTimeUtc, endTimeUtc)；
 * 位姿恰在 endTimeUtc 时刻按授权内处理；releasedAtUtc 为 NULL 表示仍生效。
 */
public record ZoneAuthorization(
        Long id,
        String requestId,
        String fingerprint,
        String craneId,
        String zoneId,
        Instant startTimeUtc,
        Instant endTimeUtc,
        BigDecimal loadTonnes,
        AuthorizationStatus status,
        Instant createdAtUtc,
        Instant releasedAtUtc) {

    /**
     * 判断给定时刻的位姿是否处于本授权保护内：
     * 起止时刻闭区间覆盖（恰结束时刻按授权内），且释放时刻之后的读数不再受保护。
     */
    public boolean coversPoseAt(Instant tsUtc) {
        boolean withinWindow = !tsUtc.isBefore(startTimeUtc) && !tsUtc.isAfter(endTimeUtc);
        boolean notReleasedBefore = releasedAtUtc == null || !tsUtc.isAfter(releasedAtUtc);
        return withinWindow && notReleasedBefore;
    }
}
