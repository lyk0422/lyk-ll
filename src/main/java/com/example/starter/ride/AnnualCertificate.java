package com.example.starter.ride;

import java.time.Instant;

/**
 * 年度检验证书实体（合成数据）。证书固定设备编号、检验合格时刻及有效截止；
 * 历史证书不可覆盖，换证仅原子切换 ride.current_certificate_id 唯一当前指针。
 *
 * @param id         证书主键
 * @param rideId     设备ID（证书固定设备编号）
 * @param passedAt   检验合格时刻（UTC精确时刻）
 * @param validUntil 有效截止（UTC），默认合格时刻+365×24小时；now<截止有效、恰截止失效
 * @param revoked    是否已撤销
 * @param revokedAt  撤销时刻（UTC），NULL 表示未撤销
 */
public record AnnualCertificate(
        long id,
        long rideId,
        Instant passedAt,
        Instant validUntil,
        boolean revoked,
        Instant revokedAt) {

    /** 默认有效截止：检验合格时刻 + 365×24 小时（合成规则，不引用真实法定周期）。 */
    public static final java.time.Duration DEFAULT_VALIDITY = java.time.Duration.ofHours(365L * 24);

    /** UTC 下 now < 截止为有效、恰截止即失效；已撤销证书一律无效。 */
    public boolean validAt(Instant now) {
        return !revoked && now.isBefore(validUntil);
    }
}
