package com.example.starter.costmap.web.dto;

import java.util.List;

/**
 * 区域栅格查询结果。
 */
public record CellRegionResponse(
        String mapId,
        RegionDto region,
        long liveSeq,
        List<CellDto> cells) {
}
