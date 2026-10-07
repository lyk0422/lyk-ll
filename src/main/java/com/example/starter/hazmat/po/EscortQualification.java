package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 接班员合成资格：人员编号 + 可押运货类 + UTC 有效区间 + 撤销状态（不对接真实资格机构）。
 *
 * @param id            主键
 * @param escortId      接班员编号（合成数据）
 * @param goodsCategory 可押运危化品类别
 * @param validFromUtc  资格有效区间起点（UTC，闭区间）
 * @param validToUtc    资格有效区间终点（UTC，闭区间）；须完整覆盖预约班段[start,end)
 * @param revoked       是否已撤销；撤销后拒绝新预约，受控段 IN 重查拒绝
 * @param versionNo     乐观锁版本：从 1 起，修改用 expectedVersion，成功加 1，过期 409
 * @param requestId     创建请求的幂等键
 * @param createdAt     创建时刻（Asia/Shanghai）
 * @param updatedAt     最近修改时刻（Asia/Shanghai）
 */
public record EscortQualification(
        long id,
        String escortId,
        String goodsCategory,
        LocalDateTime validFromUtc,
        LocalDateTime validToUtc,
        boolean revoked,
        int versionNo,
        String requestId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
