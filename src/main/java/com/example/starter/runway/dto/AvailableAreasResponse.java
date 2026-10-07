package com.example.starter.runway.dto;

import java.util.List;

/**
 * 可用区域查询响应：可用区间为所有活动施工区间并集的补集，重叠不重复计长度。
 *
 * @param runwayId               跑道编号
 * @param lengthMeters           跑道长度（单位：米）
 * @param unavailable            闭锁区间并集（已合并重叠，按起点排序）
 * @param available              可用区间列表（补集，按起点排序）；空可用返回空列表
 * @param unavailableTotalMeters 闭锁总长度（单位：米，并集口径，重叠不重复计）
 * @param availableTotalMeters   可用总长度（单位：米）
 */
public record AvailableAreasResponse(
        String runwayId,
        int lengthMeters,
        List<IntervalView> unavailable,
        List<IntervalView> available,
        int unavailableTotalMeters,
        int availableTotalMeters) {
}
