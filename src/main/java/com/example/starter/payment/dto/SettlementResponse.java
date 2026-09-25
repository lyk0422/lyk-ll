package com.example.starter.payment.dto;

import java.util.List;

/**
 * 批次封账/清算结果。replayed=true 表示批次已封账，返回首次清算单且未重新计算。
 */
public record SettlementResponse(
        String batchDate,
        String status,
        boolean replayed,
        List<SettlementItem> items) {
}
