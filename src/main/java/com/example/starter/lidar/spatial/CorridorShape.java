package com.example.starter.lidar.spatial;

import com.example.starter.lidar.model.BBox;
import com.example.starter.lidar.model.Vec3;

import java.util.List;

/**
 * 轨迹走廊判定：点到折线各段（圆柱段 + 端点球帽的并集）的最小距离不大于半宽，
 * 边界包含。折线上相邻重合顶点段会被跳过。
 */
class CorridorShape implements SpatialShape {

    private final List<Vec3> polyline;
    private final double halfWidth;
    private final double halfWidth2;
    private final BBox envelope;

    CorridorShape(List<Vec3> polyline, double halfWidth) {
        this.polyline = polyline;
        this.halfWidth = halfWidth;
        this.halfWidth2 = halfWidth * halfWidth;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3 v : polyline) {
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxY = Math.max(maxY, v.y());
            maxZ = Math.max(maxZ, v.z());
        }
        this.envelope = new BBox(
                minX - halfWidth, minY - halfWidth, minZ - halfWidth,
                maxX + halfWidth, maxY + halfWidth, maxZ + halfWidth);
    }

    @Override
    public boolean contains(double x, double y, double z) {
        for (int i = 0; i + 1 < polyline.size(); i++) {
            Vec3 a = polyline.get(i);
            Vec3 b = polyline.get(i + 1);
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double dz = b.z() - a.z();
            double len2 = dx * dx + dy * dy + dz * dz;
            double closestX;
            double closestY;
            double closestZ;
            if (len2 == 0.0) {
                // 重合顶点：退化为到顶点距离（端点球帽）
                closestX = a.x();
                closestY = a.y();
                closestZ = a.z();
            } else {
                double t = ((x - a.x()) * dx + (y - a.y()) * dy + (z - a.z()) * dz) / len2;
                t = Math.max(0.0, Math.min(1.0, t));
                closestX = a.x() + t * dx;
                closestY = a.y() + t * dy;
                closestZ = a.z() + t * dz;
            }
            double ex = x - closestX;
            double ey = y - closestY;
            double ez = z - closestZ;
            // 边界（恰好等于半宽）包含
            if (ex * ex + ey * ey + ez * ez <= halfWidth2) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BBox envelope() {
        return envelope;
    }
}
