package com.example.starter.carbon.dto;

import java.time.Instant;
import java.util.List;

/**
 * 清缴申请结果/清缴凭证视图。同 requestId 重复查询返回稳定结果。
 *
 * @param requestId       幂等键
 * @param enterpriseId    申请企业
 * @param complianceYear  履约年度
 * @param allowanceAmount 本次清缴配额量（吨CO2e）
 * @param offsetAmount    本次核销抵消信用量（吨CO2e）
 * @param certificateNo   清缴凭证号（全局唯一）
 * @param createdAt       受理时刻（UTC）
 * @param items           注销明细（配额/信用进入已注销终态）
 */
public record SurrenderResponse(
        String requestId,
        long enterpriseId,
        int complianceYear,
        long allowanceAmount,
        long offsetAmount,
        String certificateNo,
        Instant createdAt,
        List<SurrenderItemView> items) {
}
