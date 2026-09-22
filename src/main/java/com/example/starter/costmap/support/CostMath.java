package com.example.starter.costmap.support;

import com.example.starter.costmap.model.GridRegion;

/**
 * 纯函数代价计算工具：栅格索引、致命代价、膨胀衰减。
 * 代价语义：0 自由；1..253 按距离衰减的膨胀代价；254 致命障碍物；255 保留为未知（本服务不产生）。
 */
public final class CostMath {

    /** 致命障碍物代价。 */
    public static final int LETHAL = 254;

    /** 膨胀代价上限（非致命）。 */
    public static final int INFLATED_MAX = 253;

    private CostMath() {
    }

    /** 行优先字节数组下标。 */
    public static int index(int x, int y, int width) {
        return y * width + x;
    }

    /** 膨胀半径（米）对应的格点半径，向上取整，至少 0。 */
    public static int radiusCells(double inflationRadius, double resolution) {
        if (inflationRadius <= 0 || resolution <= 0) {
            return 0;
        }
        return (int) Math.ceil(inflationRadius / resolution - 1e-9);
    }

    /** 两格点间欧氏距离，单位格。 */
    public static double cellDistance(int x1, int y1, int x2, int y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 距最近障碍物某距离处的膨胀代价；超出膨胀半径返回 0。
     * 公式（公开且确定性）：round(253 * exp(-costDecay * 距离米))。
     */
    public static int inflationCost(double distanceCells, double resolution,
                                    double inflationRadius, double costDecay) {
        double distanceMeters = distanceCells * resolution;
        if (distanceMeters > inflationRadius) {
            return 0;
        }
        long cost = Math.round(INFLATED_MAX * Math.exp(-costDecay * distanceMeters));
        if (cost < 0) {
            return 0;
        }
        if (cost > INFLATED_MAX) {
            return INFLATED_MAX;
        }
        return (int) cost;
    }

    /** 将区域裁剪到地图边界；裁剪后为空返回 null。 */
    public static GridRegion clipToBounds(GridRegion region, int width, int height) {
        int xMin = Math.max(0, region.xMin());
        int yMin = Math.max(0, region.yMin());
        int xMax = Math.min(width - 1, region.xMax());
        int yMax = Math.min(height - 1, region.yMax());
        if (xMin > xMax || yMin > yMax) {
            return null;
        }
        return new GridRegion(xMin, yMin, xMax, yMax);
    }
}
