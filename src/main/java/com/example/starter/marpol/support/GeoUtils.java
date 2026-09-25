package com.example.starter.marpol.support;

import java.util.List;

/**
 * 地理计算工具（合成数据口径）。
 *
 * <p>多边形使用经纬度平面射线法判定，<b>恰落在边界线上视为区域内</b>；
 * “距最近陆地”以大圆距离（海里）计算，陆地点集合在调用处提供。</p>
 */
public final class GeoUtils {

    /** 地球平均半径（海里），1 海里 = 1852 米。 */
    public static final double EARTH_RADIUS_NM = 6371.0088 / 1.852;

    /** 边界线判定的浮点容差（度）。 */
    private static final double EPS = 1e-12;

    private GeoUtils() {
    }

    /**
     * 判断点是否在多边形内或恰在边界线上。
     *
     * @param lon     点经度
     * @param lat     点纬度
     * @param polygon 环点，按顺序排列，每点为 [经度, 纬度]，无需显式闭合
     * @return true 表示区域内（含边界）
     */
    public static boolean insideOrOnBoundary(double lon, double lat, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        int n = polygon.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i)[0];
            double yi = polygon.get(i)[1];
            double xj = polygon.get(j)[0];
            double yj = polygon.get(j)[1];

            if (onSegment(lon, lat, xj, yj, xi, yi)) {
                return true;
            }

            boolean crosses = (yi > lat) != (yj > lat)
                    && lon < (xj - xi) * (lat - yi) / ((yj - yi) == 0 ? EPS : (yj - yi)) + xi;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 两点间大圆距离（海里，Haversine）。
     */
    public static double haversineNm(double lon1, double lat1, double lon2, double lat2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_NM * c;
    }

    /**
     * 到最近陆地点的大圆距离（海里）。无陆地点时返回正无穷。
     */
    public static double nearestLandNm(double lon, double lat, List<double[]> landPoints) {
        if (landPoints == null || landPoints.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        double min = Double.POSITIVE_INFINITY;
        for (double[] p : landPoints) {
            min = Math.min(min, haversineNm(lon, lat, p[0], p[1]));
        }
        return min;
    }

    /**
     * 点 p 是否恰在线段 ab 上（叉积为 0 且落在包围盒内）。
     */
    private static boolean onSegment(double px, double py, double ax, double ay, double bx, double by) {
        double cross = (px - ax) * (by - ay) - (py - ay) * (bx - ax);
        if (Math.abs(cross) > EPS) {
            return false;
        }
        return px >= Math.min(ax, bx) - EPS && px <= Math.max(ax, bx) + EPS
                && py >= Math.min(ay, by) - EPS && py <= Math.max(ay, by) + EPS;
    }
}
