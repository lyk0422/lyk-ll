package com.example.starter.pointcloud.model;

/**
 * 三维轴对齐包围盒，边界均为闭区间。
 *
 * @param minX 最小 X，单位米（含）
 * @param minY 最小 Y，单位米（含）
 * @param minZ 最小 Z，单位米（含）
 * @param maxX 最大 X，单位米（含）
 * @param maxY 最大 Y，单位米（含）
 * @param maxZ 最大 Z，单位米（含）
 */
public record BoundingBox(double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ) {

    /**
     * 判断两个包围盒是否相交（闭区间，面接触视为相交）。
     */
    public boolean intersects(BoundingBox other) {
        return !(this.maxX < other.minX || this.minX > other.maxX
                || this.maxY < other.minY || this.minY > other.maxY
                || this.maxZ < other.minZ || this.minZ > other.maxZ);
    }
}
