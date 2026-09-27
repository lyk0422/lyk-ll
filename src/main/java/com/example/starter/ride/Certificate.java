package com.example.starter.ride;

import java.time.Instant;

/**
 * 年度检验证书实体（合成规则，不引用真实法定周期）。
 *
 * @param id           证书主键
 * @param rideId       设备编号，证书固定设备不可变更
 * @param requestId    幂等请求ID
 * @param passedAt     检验合格时刻（UTC）
 * @param validUntil   有效截止（UTC），默认合格时刻+365×24小时；now<截止有效，恰截止失效
 * @param revoked      是否已撤销
 * @param revokedAt    撤销时刻（UTC），NULL 表示未撤销
 * @param revokeReason 撤销原因，NULL 表示未撤销
 * @param version      证书版本号，从1开始，每次成功变更（如撤销）加1
 */
public record Certificate(
        long id,
        long rideId,
        String requestId,
        Instant passedAt,
        Instant validUntil,
        boolean revoked,
        Instant revokedAt,
        String revokeReason,
        long version) {

    /** 证书在给定时刻是否有效：未撤销且 now<validUntil（恰截止失效）。 */
    public boolean validAt(Instant now) {
        return !revoked && now.isBefore(validUntil);
    }
}
