package com.example.starter.domain;

import java.time.Instant;

/**
 * 阀门铅封台账行：每次登记/复封产生一行，保留历史封号链，同一阀门至多一行 ACTIVE。
 *
 * @param id           主键
 * @param valveId      阀门 ID
 * @param sealNo       铅封号，全局唯一，复封须换新号
 * @param valveVersion 封存时核对的开度版本号（阀门版本链从 1 开始，0 表示尚无版本）
 * @param sealedAt     封存时刻（UTC）
 * @param operatorA    登记人/复封核对人甲
 * @param operatorB    复封核对人乙，须与甲不同；首次登记时为 null
 * @param status       状态：ACTIVE 当前有效 / SUPERSEDED 已被复封取代（历史链）
 * @param requestId    客户端幂等键，全局唯一
 * @param fingerprint  幂等指纹：requestId 与全部业务字段的结构化 SHA-256
 * @param version      台账行版本，从 1 开始，成功变更（被取代）加 1
 * @param createdAt    登记时间（UTC）
 * @param updatedAt    最后变更时间（UTC）
 */
public record ValveSeal(long id, long valveId, String sealNo, int valveVersion, Instant sealedAt,
                        String operatorA, String operatorB, SealStatus status,
                        String requestId, String fingerprint, int version,
                        Instant createdAt, Instant updatedAt) {

    /**
     * 铅封台账状态机：ACTIVE → SUPERSEDED（复封取代，保留历史链）。
     */
    public enum SealStatus {
        /** 当前有效铅封。 */
        ACTIVE,
        /** 已被复封取代，仅作历史封号链保留。 */
        SUPERSEDED
    }
}
