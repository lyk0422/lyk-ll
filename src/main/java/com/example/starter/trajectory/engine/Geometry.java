package com.example.starter.trajectory.engine;

import com.example.starter.trajectory.domain.Vec;

import java.util.List;

/**
 * 平面几何计算工具：点到线段距离与多边形包含判断。
 */
public final class Geometry {

    private Geometry() {
    }

    /**
     * 点到线段的最短距离，单位米。
     */
    public static double distanceToSegment(Vec p, Vec a, Vec b) {
        double abx = b.x() - a.x();
        double aby = b.y() - a.y();
        double apx = p.x() - a.x();
        double apy = p.y() - a.y();
        double ab2 = abx * abx + aby * aby;
        double t = 0;
        if (ab2 > 0) {
            t = (apx * abx + apy * aby) / ab2;
            if (t > 1) {
                t = 1;
            } else if (t < 0) {
                t = 0;
            }
        }
        double cx = a.x() + t * abx;
        double cy = a.y() + t * aby;
        double dx = p.x() - cx;
        double dy = p.y() - cy;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 判断点是否位于多边形内部或边界上。采用射线法，边界上的点视为合法。
     */
    public static boolean isInsideOrOnBoundary(Vec p, List<Vec> polygon) {
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            Vec a = polygon.get(i);
            Vec b = polygon.get((i + 1) % n);
            if (distanceToSegment(p, a, b) == 0.0) {
                return true;
            }
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Vec a = polygon.get(j);
            Vec b = polygon.get(i);
            boolean intersects = (b.y() > p.y()) != (a.y() > p.y())
                    && p.x() < (a.x() - b.x()) * (p.y() - b.y()) / (a.y() - b.y()) + b.x();
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 点到多边形边界的最短距离，单位米。
     */
    public static double distanceToPolygon(Vec p, List<Vec> polygon) {
        double min = Double.POSITIVE_INFINITY;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            double d = distanceToSegment(p, polygon.get(i), polygon.get((i + 1) % n));
            if (d < min) {
                min = d;
            }
        }
        return min;
    }
}
