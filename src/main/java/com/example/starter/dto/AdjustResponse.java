package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 调账结果；replayed=true 表示调账指令号重复、返回首次结果。 */
public record AdjustResponse(
        String adjustNo,
        String channelFlowNo,
        LocalDate fromBatchDate,
        LocalDate toBatchDate,
        long amount,
        String status,
        Instant createdAt,
        boolean replayed) {
}
