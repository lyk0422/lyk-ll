package com.example.starter.lidar.spatial;

import com.example.starter.lidar.model.BBox;
import com.example.starter.lidar.model.Vec3;

/** 球域判定：点到球心欧氏距离不大于半径（球面边界包含）。 */
class RadiusShape implements SpatialShape {

    private final double cx;
    private final double cy;
    private final double cz;
    private final double r2;
    private final BBox envelope;

    RadiusShape(Vec3 center, double radius) {
        this.cx = center.x();
        this.cy = center.y();
        this.cz = center.z();
        this.r2 = radius * radius;
        this.envelope = new BBox(
                cx - radius, cy - radius, cz - radius,
                cx + radius, cy + radius, cz + radius);
    }

    @Override
    public boolean contains(double x, double y, double z) {
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;
        // 使用平方距离避免开方；边界（恰好等于半径）包含
        return dx * dx + dy * dy + dz * dz <= r2;
    }

    @Override
    public BBox envelope() {
        return envelope;
    }
}
