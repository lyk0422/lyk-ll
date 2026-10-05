package com.example.starter.gas.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 气质核验单响应。
 *
 * @param verificationNo       核验单号
 * @param requestId            提交幂等键
 * @param stationCode          交接站编码
 * @param gasDate              气量日（UTC）
 * @param voucherNo            关联交接凭证号
 * @param version              版本号（同站同日自 1 递增）
 * @param status               版本状态：ACTIVE/SUPERSEDED
 * @param sampleVolume         采样体积合计
 * @param verifiedHeatingValue 核验热值（采样体积加权）
 * @param voucherHeatingValue  凭证热值（偏差分母）
 * @param deviationRate        绝对相对偏差
 * @param conclusion           结论：CONSISTENT/QUALITY_DIFF
 * @param diffStatus           差异状态：NONE/OPEN/RESOLVED
 * @param confirmationPaused   是否暂停关联凭证双方确认
 * @param createdAt            版本提交时刻（UTC）
 */
public record VerificationResponse(
        String verificationNo,
        String requestId,
        String stationCode,
        LocalDate gasDate,
        String voucherNo,
        Integer version,
        String status,
        BigDecimal sampleVolume,
        BigDecimal verifiedHeatingValue,
        BigDecimal voucherHeatingValue,
        BigDecimal deviationRate,
        String conclusion,
        String diffStatus,
        Boolean confirmationPaused,
        Instant createdAt) {
}
