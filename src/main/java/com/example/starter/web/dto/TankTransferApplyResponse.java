package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 跨罐调拨申请受理结果：双侧预占已原子生效。
 *
 * @param transferId                调拨单ID
 * @param sourceTankId              源罐ID
 * @param targetTankId              目标罐ID
 * @param varietyNo                 调拨品种号
 * @param quantityKg                调拨量 kg
 * @param transportBatchNo          运输批号
 * @param status                    RESERVED 已双侧预占
 * @param sourceReservedKg          源罐预占中数量 kg（含本单）
 * @param targetIncomingReservedKg  目标罐接收预占 kg（含本单）
 * @param sourceVersion             操作后源罐版本
 * @param targetVersion             操作后目标罐版本
 * @param transferVersion           调拨单版本（申请成功为 1）
 * @param idempotentReplay          是否为同键同参重放
 */
public record TankTransferApplyResponse(
        Long transferId,
        Long sourceTankId,
        Long targetTankId,
        String varietyNo,
        BigDecimal quantityKg,
        String transportBatchNo,
        String status,
        BigDecimal sourceReservedKg,
        BigDecimal targetIncomingReservedKg,
        long sourceVersion,
        long targetVersion,
        long transferVersion,
        boolean idempotentReplay) implements Replayable<TankTransferApplyResponse> {

    @Override
    public TankTransferApplyResponse withReplay(boolean replay) {
        return new TankTransferApplyResponse(transferId, sourceTankId, targetTankId, varietyNo,
                quantityKg, transportBatchNo, status, sourceReservedKg, targetIncomingReservedKg,
                sourceVersion, targetVersion, transferVersion, replay);
    }
}
