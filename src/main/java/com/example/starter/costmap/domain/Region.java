package com.example.starter.costmap.domain;

/**
 * 轴对齐栅格区域，坐标为格坐标，区间左闭右开 {@code [x0,x1) x [y0,y1)}。
 *
 * @param x0 起始列（含）
 * @param y0 起始行（含）
 * @param x1 结束列（不含）
 * @param y1 结束行（不含）
 */
public record Region(int x0, int y0, int x1, int y1) {

    public int width() {
        return x1 - x0;
    }

    public int height() {
        return y1 - y0;
    }

    public int cellCount() {
        return width() * height();
    }

    public boolean contains(int x, int y) {
        return x >= x0 && x < x1 && y >= y0 && y < y1;
    }

    /**
     * 判断区域是否为合法的非空矩形（不含反转坐标）。
     */
    public boolean isValid() {
        return x0 >= 0 && y0 >= 0 && x1 > x0 && y1 > y0;
    }

    /**
     * 判断区域是否完全落在 width x height 格栅内。
     */
    public boolean fitsIn(int width, int height) {
        return isValid() && x1 <= width && y1 <= height;
    }

    /**
     * 以 margin（单位格）向四周扩张并截断到 {@code [0,width) x [0,height)}，
     * 用于计算障碍物更新受影响区域加膨胀半径后的重算包围盒。
     */
    public Region expandAndClip(int margin, int width, int height) {
        int nx0 = Math.max(0, x0 - margin);
        int ny0 = Math.max(0, y0 - margin);
        int nx1 = Math.min(width, x1 + margin);
        int ny1 = Math.min(height, y1 + margin);
        return new Region(nx0, ny0, nx1, ny1);
    }

    /**
     * 与另一区域求包围盒（并集外包矩形）。
     */
    public Region union(Region other) {
        return new Region(
                Math.min(x0, other.x0),
                Math.min(y0, other.y0),
                Math.max(x1, other.x1),
                Math.max(y1, other.y1));
    }
}
