package com.example.starter.fermentation.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 双乙酰检测记录。连续两次检测均不超过 0.10 mg/L（恰 0.10 合格）且间隔不少于 12 小时（恰 12 小时合法）方可降温。
 *
 * @param id             记录 ID
 * @param batchId        所属批次 ID
 * @param testedAt       检测时间
 * @param valueMgPerLiter 双乙酰含量，单位 mg/L
 * @param late           是否迟到数据（批次已转出后到达）
 */
public record DiacetylTest(
        long id,
        long batchId,
        LocalDateTime testedAt,
        BigDecimal valueMgPerLiter,
        boolean late) {
}
