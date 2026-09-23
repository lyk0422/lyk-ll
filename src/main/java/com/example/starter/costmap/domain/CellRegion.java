package com.example.starter.costmap.domain;

import com.example.starter.costmap.web.ApiException;
import com.example.starter.costmap.web.ErrorCode;

/**
 * 轴对齐栅格矩形区域，坐标均为单元格下标，边界含端点。
 *
 * <p>坐标系原点在左上角：x 为列（向右增大），y 为行（向下增大）。</p>
 *
 * @param x0 最小列（含）
 * @param y0 最小行（含）
 * @param x1 最大列（含）
 * @param y1 最大行（含）
 */
public record CellRegion(int x0, int y0, int x1, int y1) {

    /** 区域内单元格数量。 */
    public int area() {
        return (x1 - x0 + 1) * (y1 - y0 + 1);
    }

    /** 判断坐标是否在区域内（含边界）。 */
    public boolean contains(int x, int y) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    /** 与另一区域是否相交（含边界接触）。 */
    public boolean intersects(CellRegion other) {
        return x0 <= other.x1 && x1 >= other.x0 && y0 <= other.y1 && y1 >= other.y0;
    }

    /**
     * 校验区域非空反转且完全落在 {@code width x height} 栅格内，非法时抛
     * {@link ApiException}（INVALID_REGION）。
     */
    public CellRegion validateWithin(int width, int height) {
        if (x0 < 0 || y0 < 0 || x1 >= width || y1 >= height || x0 > x1 || y0 > y1) {
            throw new ApiException(ErrorCode.INVALID_REGION,
                    "region [" + x0 + "," + y0 + "," + x1 + "," + y1
                            + "] is empty, reversed or outside map " + width + "x" + height);
        }
        return this;
    }

    /** 向四周扩展 {@code radius} 个单元格并裁剪到栅格边界。 */
    public CellRegion expandAndClip(int radius, int width, int height) {
        int nx0 = Math.max(0, x0 - radius);
        int ny0 = Math.max(0, y0 - radius);
        int nx1 = Math.min(width - 1, x1 + radius);
        int ny1 = Math.min(height - 1, y1 + radius);
        return new CellRegion(nx0, ny0, nx1, ny1);
    }

    /** 取两个区域的最小并集包围盒。 */
    public CellRegion union(CellRegion other) {
        return new CellRegion(
                Math.min(x0, other.x0),
                Math.min(y0, other.y0),
                Math.max(x1, other.x1),
                Math.max(y1, other.y1));
    }
}
