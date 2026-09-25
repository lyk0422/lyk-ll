package com.example.starter.carbon.web;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 清缴凭证查询结果，重复查询结果稳定。
 *
 * @param certificateNo    凭证号（全局唯一）
 * @param requestId        对应清缴申请幂等键
 * @param enterpriseCode   企业编码
 * @param complianceYear   履约年度
 * @param totalRetired     注销总量（吨CO2e）
 * @param allowanceRetired 配额注销量（吨CO2e）
 * @param offsetRetired    抵消核销量（吨CO2e）
 * @param issuedUtc        凭证签发时刻（UTC）
 */
public record CertificateResponse(
        String certificateNo,
        String requestId,
        String enterpriseCode,
        Integer complianceYear,
        BigDecimal totalRetired,
        BigDecimal allowanceRetired,
        BigDecimal offsetRetired,
        Instant issuedUtc) {
}
