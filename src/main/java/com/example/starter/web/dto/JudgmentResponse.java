package com.example.starter.web.dto;

import java.util.Map;

/**
 * 判定分流响应。
 *
 * @param batchNo        屠宰批次号
 * @param status         批次状态
 * @param judgedCount    本次判定头只数
 * @param categoryCounts 按判定类别统计的头只数
 */
public record JudgmentResponse(
        String batchNo,
        String status,
        int judgedCount,
        Map<String, Integer> categoryCounts) {
}
