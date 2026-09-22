package com.example.starter.costmap.web.dto;

import java.util.List;

/**
 * 单元格代价变化。
 */
public record CellChange(int x, int y, int fromCost, int toCost) {

    /**
     * 区域差异结果。
     */
    public record DiffResponse(
            String versionId,
            String fromRef,
            String toRef,
            int xMin,
            int yMin,
            int xMax,
            int yMax,
            int changedCount,
            List<CellChange> changes) {
    }
}
