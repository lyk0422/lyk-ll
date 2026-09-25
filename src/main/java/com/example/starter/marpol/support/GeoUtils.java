package com.example.starter.marpol.support;

import java.math.BigDecimal;
import java.util.List;

/**
 * 地理计算工具（WGS84 经纬度，平面多边形判定 + 大圆距离）。
 *
 * <p>特殊区域以合成多边形表示，点恰落在边界线上视为区域内；
 * “距最近陆地”以港口/岸线参考点的大圆距离近似，1 海里 = 1852 米。
 */
public final class GeoUtils {

    /** 赤道/大圆弧上 1 海里对应的度数级误差阈值，用于边界线判定。 */
    private static final double BOUNDARY_EPSILON_DEG = 1e-7;

    /** 地球平均半径（海里），1 海里 = 1852 米。 */
    private static final double EARTH_RADIUS_NM = 6_371_000.0 / 1852.0;

    private GeoUtils() {
    }

    /**
     * 点是否位于多边形内或恰在边界线上（射线法，顶点按顺序闭合环）。
     */
    public static boolean isInsideOrOnBoundary(double lat, double lon,
                                                List<double[]> vertices) {
        if (vertices == null || vertices.size() < 3) {
            return false;
        }
        if (isOnBoundary(lat, lon, vertices)) {
            return true;
        }
        boolean inside = false;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double viLat = vertices.get(i)[0];
            double viLon = vertices.get(i)[1];
            double vjLat = vertices.get(j)[0];
            double vjLon = vertices.get(j)[1];
            boolean intersects = ((viLat > lat) != (vjLat > lat))
                    && (lon < (vjLon - viLon) * (lat - viLat) / (vjLat - viLat) + viLon);
            if (intersects) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 两点大圆距离（海里，Haversine）。
     */
    public static double nauticalMiles(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2)
                * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_NM * c;
    }

    /**
     * 到一组参考点的最小大圆距离（海里）。
     */
    public static double minNauticalMiles(double lat, double lon, List<double[]> referencePoints) {
        return referencePoints.stream()
                .mapToDouble(p -> nauticalMiles(lat, lon, p[0], p[1]))
                .min()
                .orElse(Double.POSITIVE_INFINITY);
    }

    public static double d(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    /**
     * 逐边判定点是否恰在边界线段上：先叉积判共线，再投影参数判落在线段范围内。
     */
    private static boolean isOnBoundary(double lat, double lon, List<double[]> vertices) {
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double ax = vertices.get(j)[1];
            double ay = vertices.get(j)[0];
            double bx = vertices.get(i)[1];
            double by = vertices.get(i)[0];
            double cross = (lon - ax) * (by - ay) - (lat - ay) * (bx - ax);
            double segmentLength = Math.hypot(bx - ax, by - ay);
            double distanceToLine = segmentLength == 0
                    ? Math.hypot(lon - ax, lat - ay)
                    : Math.abs(cross) / segmentLength;
            if (distanceToLine <= BOUNDARY_EPSILON_DEG
                    && lon >= Math.min(ax, bx) - BOUNDARY_EPSILON_DEG
                    && lon <= Math.max(ax, bx) + BOUNDARY_EPSILON_DEG
                    && lat >= Math.min(ay, by) - BOUNDARY_EPSILON_DEG
                    && lat <= Math.max(ay, by) + BOUNDARY_EPSILON_DEG) {
                return true;
            }
        }
        return false;
    }
}
