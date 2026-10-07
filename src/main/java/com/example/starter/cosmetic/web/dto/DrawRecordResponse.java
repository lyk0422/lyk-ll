package com.example.starter.cosmetic.web.dto;

import com.example.starter.cosmetic.domain.LabelDrawRecord;

import java.time.Instant;

/**
 * 标签领用凭据响应（追溯用）。
 *
 * @param drawId      凭据主键
 * @param printRunNo  印次号
 * @param batchNo     包装批号
 * @param productCode 产品编码
 * @param quantity    本次领用张数
 * @param drawnAtUtc  领用时刻(UTC)
 * @param riskFlag    是否已追加风险关联（所属备案修订已失效）
 * @param riskNote    风险关联说明；无风险为 null
 * @param riskAtUtc   风险关联时刻(UTC)；无风险为 null
 */
public record DrawRecordResponse(
        Long drawId,
        String printRunNo,
        String batchNo,
        String productCode,
        int quantity,
        Instant drawnAtUtc,
        boolean riskFlag,
        String riskNote,
        Instant riskAtUtc) {

    /** 由持久化记录构造响应。 */
    public static DrawRecordResponse from(LabelDrawRecord r) {
        return new DrawRecordResponse(r.id(), r.printRunNo(), r.batchNo(), r.productCode(),
                r.quantity(), r.drawnAtUtc(), r.riskFlag(), r.riskNote(), r.riskAtUtc());
    }
}
