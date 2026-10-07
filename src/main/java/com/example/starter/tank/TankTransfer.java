package com.example.starter.tank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨罐调拨单：同品种两罐间调拨加臭剂，申请时原子预占源数量与目标空间，
 * 两名不同交接人确认后同事务扣源余额、加目标余额并释放双侧预占。
 *
 * @param id               调拨单ID（双侧台账共用同一调拨标识）
 * @param requestId        申请调拨的幂等键
 * @param sourceTankId     源罐ID
 * @param targetTankId     目标罐ID，须与源罐不同
 * @param varietyNo        调拨品种号，须与两罐登记的不可变品种号一致
 * @param quantityKg       申请调拨量 kg，正数至多三位小数
 * @param transportBatchNo 全局唯一运输批号
 * @param firstOperator    第一名交接人工号
 * @param secondOperator   第二名交接人工号，须与第一人不同
 * @param status           RESERVED/EXECUTED/CANCELLED
 * @param version          调拨单乐观锁版本，从 1 开始，确认/撤销成功加 1
 * @param actualQtyKg      执行确认实际量 kg，须等于申请量；未执行为 null
 * @param credentialNo     执行确认的合成凭据号；未执行为 null
 * @param appliedAt        申请时刻（UTC）
 * @param executedAt       执行时刻（UTC），未执行为 null
 * @param cancelledAt      撤销时刻（UTC），未撤销为 null
 */
public record TankTransfer(Long id, String requestId, Long sourceTankId, Long targetTankId,
                           String varietyNo, BigDecimal quantityKg, String transportBatchNo,
                           String firstOperator, String secondOperator, String status,
                           long version, BigDecimal actualQtyKg, String credentialNo,
                           LocalDateTime appliedAt, LocalDateTime executedAt,
                           LocalDateTime cancelledAt) {
}
