package com.example.starter.bonded.domain;

import java.time.Instant;

/**
 * 转关单持久化对象。
 *
 * @param id 数据库主键
 * @param requestId 幂等请求号
 * @param enterpriseId 企业编号
 * @param fromWarehouse 起运仓编号
 * @param toWarehouse 目的仓编号
 * @param status 当前状态
 * @param fingerprint 申请指纹（requestId + 全部业务字段规范化摘要）
 * @param endorseTime 核注时间（UTC），未核注为 null
 * @param deadline 核销截止时间（UTC，核注 + 48 小时），未核注为 null
 * @param createdAt 创建时间（UTC）
 * @param updatedAt 最近变更时间（UTC）
 */
public record OrderPo(
        Long id,
        String requestId,
        String enterpriseId,
        String fromWarehouse,
        String toWarehouse,
        TransferStatus status,
        String fingerprint,
        Instant endorseTime,
        Instant deadline,
        Instant createdAt,
        Instant updatedAt) {
}
