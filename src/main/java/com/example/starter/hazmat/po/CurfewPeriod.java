package com.example.starter.hazmat.po;

import java.time.LocalDateTime;

/**
 * 夜间禁行时段（合成管制规则，全局生效，半开区间 [start,end)）。
 *
 * @param id        主键
 * @param startTime 禁行起点时刻（Asia/Shanghai），时刻==起点视为禁行（恰满起点判定）
 * @param endTime   禁行结束时刻（Asia/Shanghai），时刻==结束视为可通行
 */
public record CurfewPeriod(long id, LocalDateTime startTime, LocalDateTime endTime) {

    /** 半开区间判定：{@code start <= t < end}。 */
    public boolean contains(LocalDateTime t) {
        return !t.isBefore(startTime) && t.isBefore(endTime);
    }
}
