package com.example.rebate.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 汇总分配母凭证视图：母凭证只记一次，分配行勾稽到各追缴明细当前余额。
 *
 * @param masterSerial 唯一真实银行流水号
 * @param totalCents 到账总额（整数分），等于各分配行金额合计
 * @param receivedAt UTC 到账时刻
 * @param version 母凭证版本，恒为 1
 */
public record MasterAllocationView(
        Long masterId,
        String batchNo,
        String masterSerial,
        long totalCents,
        Instant receivedAt,
        int version,
        Instant createdAt,
        List<Item> allocations) {

    /**
     * 单行分配视图：内部分配标识、目标单与到账后该单追缴余额。
     *
     * @param allocationNo 分配行内部标识（非银行流水）
     * @param collectedCents 该单累计已收（整数分）
     * @param remainingCents 该单未收回金额（整数分）
     * @param status 该单追缴状态（COLLECTING/COMPLETED）
     * @param detailVersion 该单追缴明细当前版本
     */
    public record Item(
            String allocationNo,
            String correctionNo,
            String clawbackNo,
            long amountCents,
            long collectedCents,
            long remainingCents,
            String status,
            int detailVersion) {
    }
}
