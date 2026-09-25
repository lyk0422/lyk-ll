package com.example.starter.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 号码用户状态（subscriber 表行映射），资格校验的数据来源。
 *
 * @param arrearsAmount 欠费金额（元），0 表示无欠费
 * @param contractEndDate 在网约定期限截止日（UTC 日期）；null 表示无在网约定
 * @param lastPortTime 上次携转完成时刻（UTC）；null 表示从未携转
 */
public record SubscriberInfo(
        String msisdn,
        String currentOperator,
        BigDecimal arrearsAmount,
        LocalDate contractEndDate,
        Instant lastPortTime) {
}
