package com.example.starter.slaughter.dto;

import java.util.Map;

/**
 * 判定分流响应。
 *
 * @param batchNo        批次编号
 * @param status         批次状态
 * @param categoryCounts 各判定类别头只数：QUALIFIED/CONDITIONAL/RENDERED/DESTROYED
 */
public record JudgmentResponse(
        String batchNo,
        String status,
        Map<String, Integer> categoryCounts) {
}
