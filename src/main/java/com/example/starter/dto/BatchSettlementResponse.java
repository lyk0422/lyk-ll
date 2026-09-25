package com.example.starter.dto;

import com.example.starter.entity.SettlementOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 封账/查询批次返回：批次汇总与各商户清算单。 */
public record BatchSettlementResponse(
        LocalDate batchDate,
        String status,
        Instant closedAt,
        int merchantCount,
        long totalAmount,
        long totalFee,
        long settledAmount,
        List<SettlementOrder> orders) {
}
