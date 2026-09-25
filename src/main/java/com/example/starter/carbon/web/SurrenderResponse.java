package com.example.starter.carbon.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 清缴申请处理结果（同时用于首次受理与同键重放）。
 *
 * @param requestId       申请幂等键
 * @param enterpriseCode  企业编码
 * @param complianceYear  履约年度
 * @param status          RETIRED 已注销 / REJECTED 已拒绝
 * @param rejectReason    拒绝原因，成功为 null
 * @param allowanceAmount 配额注销量（吨CO2e）
 * @param offsetAmount    抵消核销量（吨CO2e）
 * @param certificateNo   清缴凭证号，成功时非空
 * @param retirements     首次注销清单（逐批扣减明细）
 * @param processedUtc    处理完成时刻（UTC）
 */
public record SurrenderResponse(
        String requestId,
        String enterpriseCode,
        Integer complianceYear,
        String status,
        String rejectReason,
        BigDecimal allowanceAmount,
        BigDecimal offsetAmount,
        String certificateNo,
        List<RetirementItem> retirements,
        Instant processedUtc) {
}
