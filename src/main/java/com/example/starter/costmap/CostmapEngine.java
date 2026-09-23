package com.example.starter.costmap;

import com.example.starter.costmap.model.CostmapState;
import com.example.starter.costmap.model.ObstacleRecord;
import com.example.starter.costmap.model.Region;

/**
 * 代价地图计算引擎：负责障碍物合并与膨胀层的区域重算。
 * <p>
 * 合并规则（公开且确定性）：同一格被多个障碍物覆盖时，依次按
 * 优先级大者优先、客户端时间戳大者优先、来源 ID 字典序小者优先 选出唯一胜者，
 * 该格障碍物代价取胜者对应格的值，再与静态层取较大者作为基础代价。
 * <p>
 * 膨胀规则（公开且确定性）：基础代价不小于 254 的格子为致命障碍种子；
 * 距最近种子欧氏距离 d（格）在 [1, R] 内的格子获得线性衰减代价
 * {@code round(253 * (R - d + 1) / (R + 1))}，其中 R 为膨胀半径换算的格数；
 * 最终代价取基础代价与膨胀代价的较大者。
 */
public final class CostmapEngine {

    /** 致命障碍代价值。 */
    public static final int LETHAL = 254;

    private CostmapEngine() {
    }

    /**
     * 重算指定区域（含膨胀影响）：先重算该区域的基础代价（静态层与障碍物合并），
     * 再依据全图基础代价重算该区域的膨胀叠加结果。
     * <p>
     * 调用方须保证传入区域已按膨胀半径外扩，使得区域外的基础代价变化不会影响区域内。
     *
     * @return 实际参与重算的区域（已裁剪到地图边界）
     */
    public static Region recomputeRegion(CostmapState state, Region affected) {
        int x = Math.max(0, affected.x());
        int y = Math.max(0, affected.y());
        int x2 = Math.min(state.width(), affected.x() + affected.width());
        int y2 = Math.min(state.height(), affected.y() + affected.height());
        if (x2 <= x || y2 <= y) {
            return new Region(x, y, 0, 0);
        }
        Region clipped = new Region(x, y, x2 - x, y2 - y);
        recomputeBase(state, clipped);
        recomputeInflation(state, clipped);
        return clipped;
    }

    /**
     * 膨胀半径（米）换算为格数（向上取整）。
     */
    public static int inflationCells(double inflationRadius, double resolution) {
        if (inflationRadius <= 0 || resolution <= 0) {
            return 0;
        }
        return (int) Math.ceil(inflationRadius / resolution);
    }

    /**
     * 距离 d（格）处的膨胀代价；d 在 (0, R] 内线性衰减，d 为 0 时由种子自身代价表达。
     */
    static int inflationCost(double d, int radiusCells) {
        if (d <= 0) {
            return LETHAL;
        }
        if (d > radiusCells) {
            return 0;
        }
        return Math.max(1, (int) Math.round(253.0 * (radiusCells - d + 1.0) / (radiusCells + 1.0)));
    }

    private static void recomputeBase(CostmapState state, Region region) {
        byte[] staticGrid = state.staticGrid();
        byte[] baseGrid = state.baseGrid();
        int width = state.width();
        for (int cy = region.y(); cy < region.y() + region.height(); cy++) {
            for (int cx = region.x(); cx < region.x() + region.width(); cx++) {
                int idx = cy * width + cx;
                int cost = staticGrid[idx] & 0xFF;
                ObstacleRecord winner = null;
                for (ObstacleRecord obstacle : state.obstacles()) {
                    if (obstacle.region().contains(cx, cy)
                            && (winner == null || obstacle.comparePrecedence(winner) > 0)) {
                        winner = obstacle;
                    }
                }
                if (winner != null) {
                    cost = Math.max(cost, winner.costAt(cx, cy));
                }
                baseGrid[idx] = (byte) cost;
            }
        }
    }

    private static void recomputeInflation(CostmapState state, Region region) {
        byte[] baseGrid = state.baseGrid();
        byte[] masterGrid = state.masterGrid();
        int width = state.width();
        int height = state.height();
        int radius = inflationCells(state.inflationRadius(), state.resolution());
        for (int cy = region.y(); cy < region.y() + region.height(); cy++) {
            for (int cx = region.x(); cx < region.x() + region.width(); cx++) {
                int idx = cy * width + cx;
                int cost = baseGrid[idx] & 0xFF;
                if (radius > 0 && cost < LETHAL) {
                    int x0 = Math.max(0, cx - radius);
                    int x1 = Math.min(width - 1, cx + radius);
                    int y0 = Math.max(0, cy - radius);
                    int y1 = Math.min(height - 1, cy + radius);
                    for (int ny = y0; ny <= y1; ny++) {
                        for (int nx = x0; nx <= x1; nx++) {
                            int dx = nx - cx;
                            int dy = ny - cy;
                            if (dx * dx + dy * dy > radius * radius) {
                                continue;
                            }
                            if ((baseGrid[ny * width + nx] & 0xFF) >= LETHAL) {
                                cost = Math.max(cost, inflationCost(Math.sqrt(dx * dx + dy * dy), radius));
                            }
                        }
                    }
                }
                masterGrid[idx] = (byte) cost;
            }
        }
    }
}
