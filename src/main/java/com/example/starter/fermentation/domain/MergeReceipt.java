package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合批接收受理单。一次引用同一接收罐与若干来源（2 至 5 个不同受控源批，
 * 来源须使用同一工艺曲线版本并仍满足原转出条件），完整接收各源登记量。
 *
 * @param id               受理单 ID
 * @param receivingTankId  接收入罐 ID
 * @param totalLiters      本次合计接收升数（各源登记量之和）
 * @param receivedAt       业务接收时间（调用方传入，本地时间，无时区）
 * @param createdAt        受理时间（UTC）
 */
public record MergeReceipt(
        long id,
        long receivingTankId,
        BigDecimal totalLiters,
        LocalDateTime receivedAt,
        LocalDateTime createdAt) {
}
