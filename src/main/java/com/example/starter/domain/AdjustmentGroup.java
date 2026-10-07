package com.example.starter.domain;

import java.time.Instant;

/**
 * 联调组：同站 2~5 只不同阀的耦合调节分组，版本从 1 起。
 *
 * @param id                 主键
 * @param stationId          所属换热站 ID，全部成员阀须同属该站
 * @param status             状态：ACTIVE 活动 / DISSOLVED 已解除（终态）
 * @param version            联调单版本，从 1 开始，成功联调或解除加 1，旧版提交返回 409
 * @param createdAt          建组时刻（UTC）
 * @param dissolvedAt        解除时刻（UTC）；未解除时为 null
 * @param requestId          建组请求的客户端幂等键，全局唯一
 * @param fingerprint        建组幂等指纹：requestId 与全阀集合及权重的结构化 SHA-256
 * @param dissolveRequestId  解除请求的幂等键；未解除时为 null
 * @param dissolveFingerprint 解除请求的幂等指纹；未解除时为 null
 */
public record AdjustmentGroup(long id, long stationId, GroupStatus status, int version,
                              Instant createdAt, Instant dissolvedAt,
                              String requestId, String fingerprint,
                              String dissolveRequestId, String dissolveFingerprint) {

    /**
     * 联调组状态机：ACTIVE → DISSOLVED（终态，仅释放组归属，不回拨开度或返还额度）。
     */
    public enum GroupStatus {
        /** 活动：可提交联调。 */
        ACTIVE,
        /** 已解除：终态，成员归属释放，历史保留。 */
        DISSOLVED
    }
}
