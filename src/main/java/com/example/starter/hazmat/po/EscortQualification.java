package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 接班员合成资格（合成数据，不接真实资格机构）。
 *
 * @param id            主键
 * @param escortId      接班员编号
 * @param goodsCategory 可押运货类，须与运单货类一致
 * @param validFrom     资格有效区间起点（UTC，闭区间）
 * @param validTo       资格有效区间终点（UTC，闭区间）
 * @param revoked       是否已撤销
 * @param versionNo     资格版本号：从 1 起，每次修改（含撤销）加 1
 * @param createdAt     创建时刻
 * @param revokedAt     撤销时刻；未撤销为 null
 */
public record EscortQualification(
        long id,
        String escortId,
        String goodsCategory,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        boolean revoked,
        int versionNo,
        LocalDateTime createdAt,
        LocalDateTime revokedAt) {
}
