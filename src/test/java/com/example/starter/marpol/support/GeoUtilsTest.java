package com.example.starter.marpol.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 特殊区域多边形（含边界线）与海里距离口径的纯单元测试。
 */
class GeoUtilsTest {

    /** 纬度 20~30、经度 110~120 的合成矩形。 */
    private static final List<double[]> RECTANGLE = List.of(
            new double[] {20, 110},
            new double[] {30, 110},
            new double[] {30, 120},
            new double[] {20, 120});

    /** GeoUtils 使用的地球半径（海里），用于反推恰 12 海里的坐标。 */
    private static final double EARTH_RADIUS_NM = 6_371_000.0 / 1852.0;

    @Test
    void pointInsidePolygonIsInside() {
        assertTrue(GeoUtils.isInsideOrOnBoundary(25, 115, RECTANGLE));
    }

    @Test
    void pointOnEdgeOrCornerIsConsideredInside() {
        // 恰在西边界线、北边界线与顶点上
        assertTrue(GeoUtils.isInsideOrOnBoundary(25, 110, RECTANGLE));
        assertTrue(GeoUtils.isInsideOrOnBoundary(30, 115, RECTANGLE));
        assertTrue(GeoUtils.isInsideOrOnBoundary(20, 110, RECTANGLE));
        assertTrue(GeoUtils.isInsideOrOnBoundary(30, 120, RECTANGLE));
    }

    @Test
    void pointJustOutsideIsNotInside() {
        assertFalse(GeoUtils.isInsideOrOnBoundary(30.0001, 115, RECTANGLE));
        assertFalse(GeoUtils.isInsideOrOnBoundary(25, 120.0001, RECTANGLE));
    }

    @Test
    void exactTwelveNauticalMilesIsRecognised() {
        // 沿经线向北恰 12 海里：反推纬度，距离应恰为 12.0
        double lat = 30 + Math.toDegrees(12.0 / EARTH_RADIUS_NM);
        double distance = GeoUtils.nauticalMiles(30, 122, lat, 122);
        assertTrue(Math.abs(distance - 12.0) < 1e-9,
                "恰 12 海里坐标的计算距离应等于 12，实际 " + distance);
    }
}
