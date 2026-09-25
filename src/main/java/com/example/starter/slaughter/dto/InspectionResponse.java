package com.example.starter.slaughter.dto;

/**
 * 同步检验登记/收口响应。
 *
 * @param batchNo        批次编号
 * @param status         批次状态
 * @param inspectedCount 已一一对应检验的头只数
 * @param expectedCount  申报头只总数
 * @param missingCount   漏检头只数；大于 0 时整批暂停流转
 * @param complete       检验链是否已全覆盖收口
 */
public record InspectionResponse(
        String batchNo,
        String status,
        int inspectedCount,
        int expectedCount,
        int missingCount,
        boolean complete) {
}
