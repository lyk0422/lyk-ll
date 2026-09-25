package com.example.starter.web.dto;

/**
 * 同步检验响应。
 *
 * @param batchNo        屠宰批次号
 * @param headNo         头只编号
 * @param headStatus     头只状态
 * @param inspectedCount 已检验头只数
 * @param totalHeads     批次头只总数
 */
public record InspectionResponse(
        String batchNo,
        String headNo,
        String headStatus,
        int inspectedCount,
        int totalHeads) {
}
