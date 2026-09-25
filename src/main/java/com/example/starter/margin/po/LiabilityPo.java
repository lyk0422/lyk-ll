package com.example.starter.margin.po;

import com.example.starter.margin.domain.LiabilityStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 逐笔负债持久化对象（融资欠款）。
 *
 * @param id                自增主键
 * @param accountNo         所属账户
 * @param liabilityNo       负债业务编号（唯一）
 * @param currency          币种
 * @param outstandingAmount 未偿金额，单位元，精确到分；强平按其从大到小排序
 * @param status            OUTSTANDING/REPAID
 * @param createdAt         创建时间（UTC）
 * @param repaidAt          结清时间（UTC），未结清为 null
 */
public record LiabilityPo(
        Long id,
        String accountNo,
        String liabilityNo,
        String currency,
        BigDecimal outstandingAmount,
        LiabilityStatus status,
        Instant createdAt,
        Instant repaidAt
) {
}
