package com.example.starter.slot.po;

/**
 * 上一航季同系列历史执行率（合成数据）。
 *
 * @param id            主键
 * @param prevSeason    上一航季标识
 * @param airline       航司二字码
 * @param flightNo      航班号
 * @param airport       机场三字码
 * @param slotTime      原时刻 HH:mm
 * @param executionRate 实际执行率，取值 0~100，保留一位小数；80.0 视为达标
 */
public record HistoricalPerformancePo(
        Long id,
        String prevSeason,
        String airline,
        String flightNo,
        String airport,
        String slotTime,
        double executionRate) {
}
