package com.example.starter.costmap.web.dto;

import java.util.List;

/**
 * 障碍物更新应用结果。
 *
 * @param replayed       true 表示同参幂等重放，未重复应用
 * @param statementSeq   分配的语句序号（重放时返回首次序号）
 * @param liveSeq        更新后动态视图版本序号
 * @param recomputeRegion 本次实际重算的区域（声明区域含膨胀边界，裁剪到地图）
 * @param changedCells   重算区域内代价发生变化的单元格坐标
 */
public record ObstacleResponse(
        boolean replayed,
        long statementSeq,
        long liveSeq,
        RegionDto recomputeRegion,
        List<CellDto> changedCells) {
}
