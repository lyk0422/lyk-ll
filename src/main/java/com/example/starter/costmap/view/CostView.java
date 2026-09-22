package com.example.starter.costmap.view;

import com.example.starter.costmap.model.GridRegion;

/**
 * 只读代价视图：实时视图或从 H2 快照恢复的冻结视图。
 */
public interface CostView {

    /** 视图所属地图版本ID。 */
    String versionId();

    /** 快照/恢复视图的快照ID；实时视图为 null。 */
    String snapshotId();

    /** 视图对应的更新序号（快照为发布时序号，实时为当前序号）。 */
    long seq();

    /** 单个格点总代价 0..254。 */
    int costAt(int x, int y);

    /** 区域代价矩阵，返回值 [ry][rx] 为相对区域偏移。 */
    default int[][] regionCosts(GridRegion region) {
        int w = region.xMax() - region.xMin() + 1;
        int h = region.yMax() - region.yMin() + 1;
        int[][] out = new int[h][w];
        for (int ry = 0; ry < h; ry++) {
            for (int rx = 0; rx < w; rx++) {
                out[ry][rx] = costAt(region.xMin() + rx, region.yMin() + ry);
            }
        }
        return out;
    }
}
