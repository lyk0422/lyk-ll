package com.example.rebate.domain;

import java.time.Instant;

/**
 * 汇总到账母凭证：一笔真实银行流水只记一次，分给同一结案批次内多张已批准冲回单。
 *
 * @param batchId 原结案批次
 * @param bankSerial 唯一真实银行流水号（与旧单笔回执流水全局互斥）
 * @param totalCents 到账总额（正数整数分），恒等于各分配行合计
 * @param receivedAt 到账时刻（UTC，精确时刻，来自请求）
 * @param contentFingerprint 分配内容指纹：同流水同内容重放首份分配，异内容 409
 * @param version 母凭证版本，创建为 1；母凭证不可变
 */
public record ClawbackBatchReceipt(
        Long id,
        Long batchId,
        String bankSerial,
        long totalCents,
        Instant receivedAt,
        String contentFingerprint,
        int version,
        Instant createdAt) {
}
