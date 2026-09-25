package com.example.starter.web.dto;

/**
 * 宰前申报响应。
 *
 * @param batchNo    屠宰批次号
 * @param status     批次状态
 * @param declaredAt 申报时间（UTC ISO-8601）
 * @param headCount  头只数
 */
public record DeclareResponse(
        String batchNo,
        String status,
        String declaredAt,
        int headCount) {
}
