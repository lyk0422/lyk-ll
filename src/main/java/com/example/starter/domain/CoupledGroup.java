package com.example.starter.domain;

import java.time.Instant;

/**
 * 联调组：同站 2~5 只不同阀的耦合调节分组，阀只能属于一个活动组。
 *
 * @param id          主键
 * @param stationId   所属换热站 ID
 * @param status      状态：ACTIVE 活动 / DISSOLVED 已解除
 * @param version     联调单乐观锁版本，从 1 开始，成功修改/解除加 1，旧版 409
 * @param requestId   建组客户端幂等键，全局唯一
 * @param fingerprint 幂等指纹：requestId 与全阀集合及权重的结构化 SHA-256
 * @param createdAt   建组时刻（UTC）
 * @param dissolvedAt 解除时刻（UTC）；未解除时为 null
 */
public record CoupledGroup(long id, long stationId, GroupStatus status, int version,
                           String requestId, String fingerprint,
                           Instant createdAt, Instant dissolvedAt) {

    /**
     * 联调组状态机：ACTIVE → DISSOLVED（终态，仅释放组归属）。
     */
    public enum GroupStatus {
        /** 活动：可提交联调。 */
        ACTIVE,
        /** 已解除：仅释放组归属，不回拨开度或返还已消费额度。 */
        DISSOLVED
    }
}
