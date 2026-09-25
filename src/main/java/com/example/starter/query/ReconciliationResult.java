package com.example.starter.query;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日结勾稽复算结果：完全由当日原始检测读数重算累计流量、理论消耗与偏差。
 *
 * @param bizDate           业务日期
 * @param totalFlowM3       复算累计流量 m3
 * @param expectedOdorantMg 复算理论消耗 mg = 累计流量 × 目标浓度
 * @param reportedOdorantMg 日结登记的实际上报消耗 mg
 * @param deviationPct      复算偏差百分比（恰 3.00% 合格）
 * @param sealed            日结是否已封存
 * @param readingCount      参与复算的有效检测读数条数
 */
public record ReconciliationResult(
        LocalDate bizDate,
        BigDecimal totalFlowM3,
        BigDecimal expectedOdorantMg,
        BigDecimal reportedOdorantMg,
        BigDecimal deviationPct,
        boolean sealed,
        int readingCount) {
}
