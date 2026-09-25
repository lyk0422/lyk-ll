package com.example.starter.ili;

import java.time.Instant;
import java.time.LocalDate;

/**
 * ili_batch 表行：一次双回合缺陷导入与对齐的幂等批次。
 *
 * @param id          批次主键
 * @param requestId   客户端幂等请求标识，全局唯一
 * @param fingerprint 请求规范化摘要（SHA-256 十六进制）
 * @param segmentId   管段标识
 * @param runOldId    上一检测回合标识
 * @param runNewId    本检测回合标识
 * @param runOldDate  上一回合检测日期
 * @param runNewDate  本回合检测日期，必须晚于上一回合
 * @param status      批次状态：ALIGNED=已对齐待复核，SEALED=已封板（终态）
 * @param createdAt   批次创建时间（UTC）
 * @param sealedAt    封板时间（UTC）；null 表示未封板
 */
public record BatchRow(
        long id,
        String requestId,
        String fingerprint,
        String segmentId,
        String runOldId,
        String runNewId,
        LocalDate runOldDate,
        LocalDate runNewDate,
        BatchStatus status,
        Instant createdAt,
        Instant sealedAt) {
}
