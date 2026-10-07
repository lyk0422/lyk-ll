package com.example.starter.runway.dto;

import java.util.List;

/**
 * 可用区域查询响应：所有活动施工区间并集的补集；重叠不重复计长度；空可用返回空列表。
 *
 * @param runwayId          受控跑道编号
 * @param lengthMeters      跑道长度（单位：米）
 * @param usableSegments    可用区间列表（半开，按起点升序；无可用时为空列表）
 * @param totalUsableMeters 可用总长度（单位：米，并集重叠部分不重复计）
 */
public record UsableSegmentsResponse(
        String runwayId,
        int lengthMeters,
        List<UsableSegment> usableSegments,
        int totalUsableMeters) {
}
