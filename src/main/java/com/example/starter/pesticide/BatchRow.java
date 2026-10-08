package com.example.starter.pesticide;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 药剂批号数据行；时间字段均为 UTC，空表示尚未发生。
 *
 * @param id 主键
 * @param pesticideId 所属药剂
 * @param batchNo 批号，同一药剂内唯一
 * @param quantity 该批号当前库存量
 * @param expiryDate 该批号有效期截止日（含当日）
 * @param status 状态：ACTIVE 可用 / RECALLED 已召回（不可撤回）
 * @param version 乐观锁版本：1 起，召回成功加 1
 * @param recallReason 召回原因，空表示未召回
 * @param recalledAt 召回生效时间（UTC），空表示未召回
 * @param createdAt 批号建档时间（UTC）
 */
public record BatchRow(long id, long pesticideId, String batchNo, BigDecimal quantity,
        LocalDate expiryDate, String status, int version, String recallReason,
        LocalDateTime recalledAt, LocalDateTime createdAt) {
}
