package com.example.starter.marpol.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地理口径单元测试：边界线视为区域内；恰 12 海里合法。
 */
class GeoUtilsTest {

    private final List<double[]> square = List.of(
            new double[]{0, 0}, new double[]{10, 0}, new double[]{10, 10}, new double[]{0, 10});

    @Test
    void insidePolygonReturnsTrue() {
        assertTrue(GeoUtils.insideOrOnBoundary(5, 5, square));
    }

    @Test
    void outsidePolygonReturnsFalse() {
        assertFalse(GeoUtils.insideOrOnBoundary(-1, 5, square));
        assertFalse(GeoUtils.insideOrOnBoundary(11, 11, square));
    }

    @Test
    void pointExactlyOnEdgeIsInside() {
        // 恰在底边 y=0 上，必须判定为区域内。
        assertTrue(GeoUtils.insideOrOnBoundary(5, 0, square));
        // 恰在右侧边 x=10 上。
        assertTrue(GeoUtils.insideOrOnBoundary(10, 5, square));
        // 恰在顶点上。
        assertTrue(GeoUtils.insideOrOnBoundary(0, 10, square));
    }

    @Test
    void nearestLandExactlyTwelveNauticalMilesIsAtThreshold() {
        double exactLat = Math.toDegrees(12.0 / GeoUtils.EARTH_RADIUS_NM);
        double distance = GeoUtils.nearestLandNm(0, exactLat, List.of(new double[]{0, 0}));
        assertEquals(12.0, distance, 1e-9, "恰 12 海里口径必须精确");
        // 近一微度即不足 12 海里。
        double tooClose = GeoUtils.nearestLandNm(0, exactLat - 1e-6, List.of(new double[]{0, 0}));
        assertTrue(tooClose < 12.0);
        // 远一微度即合法。
        double legal = GeoUtils.nearestLandNm(0, exactLat + 1e-6, List.of(new double[]{0, 0}));
        assertTrue(legal > 12.0);
    }
}
