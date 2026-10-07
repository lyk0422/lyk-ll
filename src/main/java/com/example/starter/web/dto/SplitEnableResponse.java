package com.example.starter.web.dto;

/**
 * 启用分车出厂响应。
 *
 * @param batchNo        屠宰批次号
 * @param status         批次当前状态
 * @param splitEnabled   是否已启用分车
 * @param qualifiedTotal 本批合格（QUALIFIED）头只总数，取自原判定明细
 */
public record SplitEnableResponse(
        String batchNo,
        String status,
        boolean splitEnabled,
        int qualifiedTotal) {
}
