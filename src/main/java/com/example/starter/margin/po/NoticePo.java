package com.example.starter.margin.po;

import com.example.starter.margin.domain.NoticeStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 追保通知持久化对象。同一账户同一未结清周期仅允许一条 OPEN（数据库唯一索引 ux_notice_open 保证）。
 *
 * @param id           自增主键
 * @param accountNo    账户号
 * @param status       OPEN/RESOLVED/EXPIRED
 * @param triggerRatio 触发时维持担保比例
 * @param deadline     追保截止时间（次一交易日 15:00，UTC+8，以 UTC 时刻存储）
 * @param createdAt    通知生成时间（UTC）
 * @param resolvedAt   解除/逾期时间（UTC），OPEN 时为 null
 */
public record NoticePo(
        Long id,
        String accountNo,
        NoticeStatus status,
        BigDecimal triggerRatio,
        Instant deadline,
        Instant createdAt,
        Instant resolvedAt
) {
}
