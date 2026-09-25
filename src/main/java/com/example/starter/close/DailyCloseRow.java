package com.example.starter.close;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 日结行。
 *
 * @param status           SEALED 封存终态 / MANUAL 转人工
 * @param totalFlowM3      当日有效检测累计流量 m3
 * @param expectedOdorantMg 理论消耗 mg
 * @param reportedOdorantMg 实际上报消耗 mg
 * @param deviationPct     勾稽偏差百分比（恰 3.00% 合格）
 * @param sealRequestId    封存请求 requestId（转人工时为空）
 * @param sealedAt         封存时刻，未封存为空
 */
public record DailyCloseRow(
        Long stationId,
        LocalDate bizDate,
        String status,
        BigDecimal totalFlowM3,
        BigDecimal expectedOdorantMg,
        BigDecimal reportedOdorantMg,
        BigDecimal deviationPct,
        String sealRequestId,
        LocalDateTime sealedAt) {
}
