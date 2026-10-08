package com.example.starter.treatment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 治理投放记录数据行；时间字段均为 UTC，空表示尚未发生。
 *
 * @param id 主键
 * @param caseId 所属处置链
 * @param pesticideId 投放药剂
 * @param batchId 投放批号，空表示未启用批号管理的历史投放
 * @param dosage 用量
 * @param placementPoint 投放点位
 * @param status 状态：PENDING 待复核 / EFFECTIVE 已生效
 * @param recallStatus 召回状态：NONE 未召回 / RECALLED 已召回待补投 / REPLACED 已补投
 * @param replacesPlacementId 补投来源（被替代的被召回投放），空表示非补投记录
 * @param version 乐观锁版本：1 起，被补投成功加 1
 * @param firstRegistrar 第一登记人
 * @param firstRegisteredAt 第一登记时间（UTC）
 * @param secondRegistrar 第二登记人，空表示尚未复核
 * @param secondRegisteredAt 第二登记时间（UTC）
 * @param placedAt 生效时间（UTC）
 */
public record PlacementRow(long id, long caseId, long pesticideId, Long batchId, BigDecimal dosage,
        String placementPoint, String status, String recallStatus, Long replacesPlacementId,
        int version, String firstRegistrar, LocalDateTime firstRegisteredAt,
        String secondRegistrar, LocalDateTime secondRegisteredAt, LocalDateTime placedAt) {
}
