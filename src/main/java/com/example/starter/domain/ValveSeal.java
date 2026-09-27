package com.example.starter.domain;

import java.time.Instant;

/**
 * 平衡阀铅封台账：登记唯一铅封号、封存时开度版本与封存时刻，复封保留历史封号链。
 *
 * @param id           主键
 * @param valveId      受控阀门 ID
 * @param sealCode     铅封号，全局唯一（含历史封号）
 * @param valveVersion 封存时阀门开度版本：无变更为 0，首次成功调节后为 1，逐次 +1
 * @param status       状态：ACTIVE 生效中 / RESEALED 已复封（历史封号，终态）
 * @param sealedAt     封存时刻（UTC）
 * @param resealedAt   复封终结时刻（UTC）；生效中为 null
 * @param createdAt    台账登记时间（UTC）
 */
public record ValveSeal(long id, long valveId, String sealCode, int valveVersion,
                        SealStatus status, Instant sealedAt, Instant resealedAt, Instant createdAt) {

    /**
     * 铅封状态机：ACTIVE → RESEALED（终态，历史封号保留）。
     */
    public enum SealStatus {
        /** 生效中：完整铅封下新的调节令必须拒绝。 */
        ACTIVE,
        /** 已复封：被新铅封终结的历史封号，仅保留证据。 */
        RESEALED
    }
}
