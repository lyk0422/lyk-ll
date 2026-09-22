package com.example.starter.costmap.support;

import com.example.starter.costmap.model.GridRegion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CostMath} 纯函数测试：膨胀半径换算、衰减代价边界、区域裁剪。
 */
class CostMathTest {

    @Test
    void radiusCellsRoundsUpAndClamps() {
        assertEquals(3, CostMath.radiusCells(2.1, 1.0));
        assertEquals(2, CostMath.radiusCells(2.0, 1.0));
        assertEquals(0, CostMath.radiusCells(0, 1.0));
        assertEquals(5, CostMath.radiusCells(1.0, 0.2));
    }

    @Test
    void inflationCostFollowsPublishedFormulaAndBoundary() {
        // 致命格点由调用方处理；膨胀格点按 round(253*exp(-decay*d))，半径外为 0
        assertEquals(253, CostMath.inflationCost(0, 1.0, 2.0, 1.0));
        assertEquals(93, CostMath.inflationCost(1, 1.0, 2.0, 1.0));
        assertEquals(34, CostMath.inflationCost(2, 1.0, 2.0, 1.0));
        // 半径边界（2 米）内仍有代价，超出半径为 0
        assertTrue(CostMath.inflationCost(2.0, 1.0, 2.0, 1.0) > 0);
        assertEquals(0, CostMath.inflationCost(2.01, 1.0, 2.0, 1.0));
    }

    @Test
    void clipToBoundsReturnsNullWhenOutside() {
        assertNull(CostMath.clipToBounds(new GridRegion(5, 5, 6, 6), 4, 4));
        assertEquals(new GridRegion(0, 0, 2, 3),
                CostMath.clipToBounds(new GridRegion(-2, -1, 2, 9), 4, 4));
    }

    @Test
    void regionDilateIsClipped() {
        assertEquals(new GridRegion(0, 0, 4, 4),
                new GridRegion(0, 0, 2, 2).dilate(3, 5, 5));
        assertEquals(new GridRegion(2, 2, 4, 4),
                new GridRegion(4, 4, 4, 4).dilate(2, 5, 5));
    }
}
