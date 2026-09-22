package com.example.starter.costmap.model;

/**
 * 栅格轴对齐区域，坐标均为含端点的格点索引，x 为列、y 为行，原点 (0,0)。
 *
 * @param xMin 最小列（含）
 * @param yMin 最小行（含）
 * @param xMax 最大列（含）
 * @param yMax 最大行（含）
 */
public record GridRegion(int xMin, int yMin, int xMax, int yMax) {

    /** 区域是否为合法矩形（含端点且非空）。 */
    public boolean isValid() {
        return xMin <= xMax && yMin <= yMax;
    }

    /** 区域包含的格点数量。 */
    public int area() {
        return (xMax - xMin + 1) * (yMax - yMin + 1);
    }

    /** 是否与另一区域相交。 */
    public boolean intersects(GridRegion other) {
        return !(xMax < other.xMin || xMin > other.xMax || yMax < other.yMin || yMin > other.yMax);
    }

    /** 向外扩张 cells 格并裁剪到 [0,width) x [0,height)。 */
    public GridRegion dilate(int cells, int width, int height) {
        int nxMin = Math.max(0, xMin - cells);
        int nyMin = Math.max(0, yMin - cells);
        int nxMax = Math.min(width - 1, xMax + cells);
        int nyMax = Math.min(height - 1, yMax + cells);
        return new GridRegion(nxMin, nyMin, nxMax, nyMax);
    }
}
