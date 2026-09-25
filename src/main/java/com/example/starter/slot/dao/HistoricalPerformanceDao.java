package com.example.starter.slot.dao;

import com.example.starter.slot.po.HistoricalPerformancePo;

import java.util.Optional;

/**
 * 上一航季历史执行率数据访问边界（骨架，待实现）。
 */
public interface HistoricalPerformanceDao {

    Optional<HistoricalPerformancePo> find(String prevSeason, String airline, String flightNo,
                                           String airport, String slotTime);
}
