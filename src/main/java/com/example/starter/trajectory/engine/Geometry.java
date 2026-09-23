package com.example.starter.trajectory.engine;

import com.example.starter.trajectory.dto.Point;

import java.util.List;

/**
 * 平面几何计算工具，全部使用局部二维坐标，单位米。
 * 不依赖任何对象分配以外的状态，便于并行规则共享。
 */
final class Geometry {

    /** 边界重合判定容差，单位米，吸收双精度多边形顶点重合误差。 */
    private static final double EPSILON = 1e-9;

    private Geometry() {
    }

    /** 两点欧氏距离。 */
    static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 两点欧氏距离。 */
    static double distance(Point a, Point b) {
        return distance(a.x(), a.y(), b.x(), b.y());
    }

    /**
     * 点到线段 ab 的最短距离。
     */
    static double distanceToSegment(Point p, Point a, Point b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared <= EPSILON) {
            return distance(p, a);
        }
        double t = ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        double projectionX = a.x() + t * dx;
        double projectionY = a.y() + t * dy;
        return distance(p.x(), p.y(), projectionX, projectionY);
    }

    /**
     * 点到多边形边界（闭合边链）的最短距离。
     */
    static double distanceToPolygonBoundary(Point p, List<? extends Point> polygon) {
        double min = Double.POSITIVE_INFINITY;
        int n = polygon.size();
        for (int i = 0; i < n; i++) {
            Point a = polygon.get(i);
            Point b = polygon.get((i + 1) % n);
            min = Math.min(min, distanceToSegment(p, a, b));
        }
        return min;
    }

    /**
     * 点是否严格位于多边形内部（射线法）；边上的点返回 {@code false}，
     * 调用方需先用 {@link #distanceToPolygonBoundary} 判定边界合法性。
     */
    static boolean isInsidePolygon(Point p, List<? extends Point> polygon) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Point a = polygon.get(j);
            Point b = polygon.get(i);
            boolean crosses = (a.y() > p.y()) != (b.y() > p.y());
            if (crosses) {
                double xAtRay = a.x() + (p.y() - a.y()) * (b.x() - a.x()) / (b.y() - a.y());
                if (xAtRay > p.x()) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    /**
     * 三点 a、b、c 的外接圆曲率绝对值，κ = 4·面积 / (|ab|·|bc|·|ca|)；
     * 共线或存在重合点时曲率为 0。
     */
    static double curvature(Point a, Point b, Point c) {
        double ab = distance(a, b);
        double bc = distance(b, c);
        double ca = distance(c, a);
        double perimeter = ab + bc + ca;
        if (perimeter <= EPSILON) {
            return 0.0;
        }
        double s = perimeter / 2.0;
        double areaSquared = s * (s - ab) * (s - bc) * (s - ca);
        if (areaSquared <= 0.0) {
            return 0.0;
        }
        double area = Math.sqrt(areaSquared);
        return 4.0 * area / (ab * bc * ca);
    }
}
