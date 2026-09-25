package com.example.starter.web.dto;

/**
 * 宰前检疫响应。
 *
 * @param batchNo          屠宰批次号
 * @param status           批次状态
 * @param preQuarantinedAt 宰前检疫通过时间（UTC ISO-8601）
 */
public record PreQuarantineResponse(
        String batchNo,
        String status,
        String preQuarantinedAt) {
}
