package com.example.starter.costmap.model;

/**
 * 地图上的矩形区域（单位：格，左上角原点，x 向右、y 向下）。
 *
 * @param x      左上角 x 坐标（含），从 0 开始
 * @param y      左上角 y 坐标（含），从 0 开始
 * @param width  区域宽度（格），至少为 1
 * @param height 区域高度（格），至少为 1
 */
public record Region(int x, int y, int width, int height) {

    /**
     * 判断该区域是否完整落在给定尺寸的地图内。
     */
    public boolean fitsWithin(int mapWidth, int mapHeight) {
        return x >= 0 && y >= 0 && width >= 1 && height >= 1
                && x + width <= mapWidth && y + height <= mapHeight;
    }

    /**
     * 按给定外扩格数扩张区域并裁剪到地图边界，用于膨胀层影响范围计算。
     */
    public Region expand(int cells, int mapWidth, int mapHeight) {
        int nx = Math.max(0, x - cells);
        int ny = Math.max(0, y - cells);
        int nx2 = Math.min(mapWidth, x + width + cells);
        int ny2 = Math.min(mapHeight, y + height + cells);
        return new Region(nx, ny, nx2 - nx, ny2 - ny);
    }

    /**
     * 判断给定格子是否位于区域内。
     */
    public boolean contains(int cx, int cy) {
        return cx >= x && cx < x + width && cy >= y && cy < y + height;
    }
}
