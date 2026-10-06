package com.example.starter.waste;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 日结响应：按品类汇总当日进场净重（进）与回执签收净重（出），可由联单明细复算。
 */
public record DailySettlementResponse(LocalDate date, List<Item> items) {

    /**
     * 单品类汇总项。
     *
     * @param category 垃圾品类
     * @param inboundKg 当日进场净重合计，单位千克
     * @param outboundKg 当日回执签收净重合计，单位千克
     * @param manifestCount 当日登记联单数
     * @param receiptCount 当日回执数
     */
    public record Item(
            String category,
            BigDecimal inboundKg,
            BigDecimal outboundKg,
            long manifestCount,
            long receiptCount) {
    }
}
