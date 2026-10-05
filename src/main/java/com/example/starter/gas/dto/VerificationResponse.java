package com.example.starter.gas.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 气质组分核验单响应。
 *
 * @param verificationNo       核验单号
 * @param requestId            提交幂等键
 * @param stationCode          交接站编码
 * @param gasDate              气量日（UTC）
 * @param voucherNo            关联的原始凭证号
 * @param version              版本号（同站同日自 1 递增）
 * @param sampleCount          参与加权的同日采样条数
 * @param verifiedHeatingValue 核验热值（同日采样体积加权）
 * @param voucherHeatingValue  核验时凭证加权热值快照
 * @param relativeDeviation    绝对相对偏差（以凭证热值为分母）
 * @param differenceFlag       气质差异标记：true 偏差>1%，false 一致（恰 1% 视为一致）
 * @param status               结论状态：CONSISTENT/DIFFERENT_OPEN/DIFFERENT_RESOLVED
 * @param confirmPaused        关联凭证当前是否因差异未解决而暂停双方确认
 * @param signOffA             A 方是否已签认差异
 * @param signOffB             B 方是否已签认差异
 * @param resolvedAt           差异解除时刻（UTC），未解除为空
 * @param createdAt            本版本提交时刻（UTC）
 */
public record VerificationResponse(
        String verificationNo,
        String requestId,
        String stationCode,
        LocalDate gasDate,
        String voucherNo,
        Integer version,
        Integer sampleCount,
        BigDecimal verifiedHeatingValue,
        BigDecimal voucherHeatingValue,
        BigDecimal relativeDeviation,
        boolean differenceFlag,
        String status,
        boolean confirmPaused,
        boolean signOffA,
        boolean signOffB,
        Instant resolvedAt,
        Instant createdAt) {
}
