package com.example.starter.pointcloud.model;

import java.util.List;

/**
 * 轨迹走廊：由折线（相邻顶点构成的线段序列）及其半宽定义的三维柱体。
 * 点到折线任意一条线段的三维距离不超过 {@code halfWidth} 即位于走廊内（闭区间）。
 *
 * @param vertices  折线顶点，至少 2 个
 * @param halfWidth 走廊半宽，单位米（含边界）
 */
public record Corridor(List<Vec3> vertices, double halfWidth) {

    /**
     * 判断点是否在走廊内（含边界）。
     */
    public boolean contains(double x, double y, double z) {
        double r2 = halfWidth * halfWidth;
        for (int i = 0; i + 1 < vertices.size(); i++) {
            Vec3 a = vertices.get(i);
            Vec3 b = vertices.get(i + 1);
            if (segmentDistanceSquared(x, y, z, a, b) <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 点到线段距离的平方；投影点落在延长线上时夹取到端点。
     */
    private static double segmentDistanceSquared(double px, double py, double pz, Vec3 a, Vec3 b) {
        double vx = b.x() - a.x();
        double vy = b.y() - a.y();
        double vz = b.z() - a.z();
        double wx = px - a.x();
        double wy = py - a.y();
        double wz = pz - a.z();
        double len2 = vx * vx + vy * vy + vz * vz;
        double t = len2 == 0.0 ? 0.0 : (wx * vx + wy * vy + wz * vz) / len2;
        if (t < 0.0) {
            t = 0.0;
        } else if (t > 1.0) {
            t = 1.0;
        }
        double dx = wx - t * vx;
        double dy = wy - t * vy;
        double dz = wz - t * vz;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 走廊的包围盒（顶点包围盒外扩半宽），用于块级粗过滤。
     */
    public BoundingBox boundingBox() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 v : vertices) {
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxY = Math.max(maxY, v.y());
            maxZ = Math.max(maxZ, v.z());
        }
        return new BoundingBox(minX - halfWidth, minY - halfWidth, minZ - halfWidth,
                maxX + halfWidth, maxY + halfWidth, maxZ + halfWidth);
    }
}
