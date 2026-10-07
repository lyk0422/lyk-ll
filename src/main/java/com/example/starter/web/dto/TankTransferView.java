package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 跨罐调拨单视图（查询）。
 *
 * @param transferId       调拨单ID
 * @param sourceTankId     源罐ID
 * @param targetTankId     目标罐ID
 * @param varietyNo        调拨品种号
 * @param quantityKg       申请调拨量 kg
 * @param transportBatchNo 全局唯一运输批号
 * @param status           RESERVED/EXECUTED/CANCELLED
 * @param version          调拨单当前版本
 * @param firstOperator    第一名交接人工号
 * @param secondOperator   第二名交接人工号
 * @param actualQtyKg      执行确认实际量 kg，未执行为 null
 * @param credentialNo     合成凭据号，未执行为 null
 * @param appliedAt        申请时刻（UTC）
 * @param executedAt       执行时刻（UTC），未执行为 null
 * @param cancelledAt      撤销时刻（UTC），未撤销为 null
 */
public record TankTransferView(
        Long transferId,
        Long sourceTankId,
        Long targetTankId,
        String varietyNo,
        BigDecimal quantityKg,
        String transportBatchNo,
        String status,
        long version,
        String firstOperator,
        String secondOperator,
        BigDecimal actualQtyKg,
        String credentialNo,
        Instant appliedAt,
        Instant executedAt,
        Instant cancelledAt) {
}
