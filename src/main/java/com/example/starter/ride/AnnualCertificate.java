package com.example.starter.ride;

import java.time.Instant;

/**
 * 年度证书实体（合成规则，不引用真实法定周期）。
 * 证书只增不改：事实字段（设备编号、合格时刻、截止时刻）不可覆盖，
 * 状态仅沿 ACTIVE → SUPERSEDED/REVOKED 流转；当前唯一指针存于 ride.current_certificate_id。
 *
 * @param id               证书主键
 * @param rideId           固定设备编号，证书不可跨设备使用
 * @param certificateNo    合成证书编号（同设备内唯一）
 * @param cycleVersion     发证时检验周期版本快照
 * @param overhaulVersion  发证时大修版本快照；登记大修后旧证快照版本落后，
 *                         旧证可查但不足以复开，须换证后与当前大修版本一致才通过门禁
 * @param issuedAt         检验合格时刻（UTC 精确时刻）
 * @param expiresAt        有效截止（UTC 精确时刻），默认合格时刻加365×24小时
 * @param status           证书状态，见 {@link CertStatus}
 * @param revokedAt        撤销时刻（UTC），NULL 表示未撤销
 * @param requestId        发证/换证请求ID
 */
public record AnnualCertificate(
        long id,
        long rideId,
        String certificateNo,
        int cycleVersion,
        int overhaulVersion,
        Instant issuedAt,
        Instant expiresAt,
        CertStatus status,
        Instant revokedAt,
        String requestId) {

    /** 证书状态。 */
    public enum CertStatus {
        /** 当前有效且被当前指针指向，同时快照大修版本与当前一致 */
        ACTIVE,
        /** 换证后成为历史证书，可查但不具开放资格 */
        SUPERSEDED,
        /** 已撤销，可查 */
        REVOKED
    }

    /**
     * 按合成规则判定截止有效性：UTC 下 now &lt; 截止有效，恰截止失效。
     * 大修版本快照须与当前一致由服务层门禁额外校验。
     */
    public boolean validAt(Instant now) {
        return status == CertStatus.ACTIVE && now.isBefore(expiresAt);
    }
}
