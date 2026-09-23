package com.example.starter.lidar.model;

import jakarta.validation.constraints.AssertTrue;

/**
 * 三维轴对齐包围盒，所有边界均为闭区间。
 *
 * @param minX X 下界（包含）
 * @param minY Y 下界（包含）
 * @param minZ Z 下界（包含）
 * @param maxX X 上界（包含）
 * @param maxY Y 上界（包含）
 * @param maxZ Z 上界（包含）
 */
public record BBox(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
) {

    /** 边界坐标必须满足下界不大于上界。 */
    @AssertTrue(message = "包围盒下界不能大于上界")
    public boolean isValid() {
        return minX <= maxX && minY <= maxY && minZ <= maxZ;
    }

    /**
     * 判断点是否位于闭区间包围盒内（边界包含）。
     */
    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * 判断两个闭区间包围盒是否相交（含边界接触）。用于块级粗筛。
     */
    public boolean intersects(BBox other) {
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }
}
