package com.example.rebate.domain;

import java.time.Instant;

/**
 * 汇总分配母凭证：一笔真实银行到账只记一次，分摊到同一结案批次内多张已批准冲回单。
 *
 * @param batchId 引用的原结案批次（批次始终结案，不回改）
 * @param masterSerial 唯一真实银行流水号（与单笔回执流水全局互斥）
 * @param totalCents 到账总额（正数整数分），精确等于各分配行合计
 * @param receivedAt 到账业务时刻（UTC，来自请求）
 * @param contentFingerprint 分配内容指纹：同流水同内容重放首份分配，异内容 409
 * @param responseBody 首份分配结果快照（JSON），重放时原样返回
 * @param version 母凭证版本，恒为 1（只记一次，不可修改）
 */
public record ClawbackMasterReceipt(
        Long id,
        Long batchId,
        String masterSerial,
        long totalCents,
        Instant receivedAt,
        String contentFingerprint,
        String responseBody,
        int version,
        Instant createdAt) {
}
