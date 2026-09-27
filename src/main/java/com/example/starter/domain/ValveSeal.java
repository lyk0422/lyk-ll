package com.example.starter.domain;

import java.time.Instant;

/**
 * 阀门铅封台账：登记/复封历史链，同一阀门至多一条 ACTIVE。
 *
 * @param id           主键
 * @param valveId      受控阀门 ID
 * @param sealNo       铅封号，全局唯一
 * @param valveVersion 登记/复封时核对的开度版本（无版本为 0）
 * @param status       状态：ACTIVE 完整有效 / RESEALED 已被复封替换
 * @param version      乐观锁版本，从 1 开始，成功变更加 1
 * @param sealedAt     封存时刻（UTC）
 * @param resealedAt   被复封替换时刻（UTC）；未替换时为 null
 * @param requestId    客户端幂等键（登记或复封请求的键），全局唯一
 * @param fingerprint  幂等指纹：requestId 与全部业务字段的结构化 SHA-256
 */
public record ValveSeal(long id, long valveId, String sealNo, int valveVersion,
                        SealStatus status, int version, Instant sealedAt, Instant resealedAt,
                        String requestId, String fingerprint) {

    /**
     * 铅封状态机：ACTIVE → RESEALED（终态，历史保留）。
     */
    public enum SealStatus {
        /** 完整有效：禁止新的调节令。 */
        ACTIVE,
        /** 已被复封替换：仅作历史封号链保留。 */
        RESEALED
    }
}
