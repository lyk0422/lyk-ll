package com.example.starter.costmap.cost;

import com.example.starter.costmap.domain.ObstacleRow;
import com.example.starter.costmap.domain.Region;
import com.example.starter.costmap.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 膨胀层指数衰减计算单元测试。
 */
class InflationCalculatorTest {

    private ObstacleRow winner(int x, int y, int cost) {
        return new ObstacleRow("m", "r", "src", SourceType.SENSOR,
                x, y, cost, 1L, 1L, null, 1L, false);
    }

    private Region region(int x0, int y0, int x1, int y1) {
        return new Region(x0, y0, x1, y1);
    }

    @Test
    void exponentialDecayAlongAxis() {
        // 障碍 (2,2) cost=100，decay=0.5，半径 2
        int[] costs = InflationCalculator.inflate(
                region(0, 0, 5, 5), List.of(winner(2, 2, 100)), 2, 0.5);

        assertEquals(0, costs[2 * 5 + 2], "障碍格自身不由膨胀层贡献");
        assertEquals(50, costs[3 * 5 + 2], "距离 1 格：floor(100*0.5)=50");
        assertEquals(25, costs[4 * 5 + 2], "距离 2 格：floor(100*0.25)=25");
        assertEquals(25, costs[0 * 5 + 2], "反方向距离 2 格同样膨胀 25");
    }

    @Test
    void diagonalDistanceUsesEuclidean() {
        int[] costs = InflationCalculator.inflate(
                region(0, 0, 4, 4), List.of(winner(1, 1, 100)), 2, 0.5);

        // (2,2) 距 (1,1) 为 sqrt(2)，100*0.5^sqrt(2) ≈ 37.5
        assertEquals(37, costs[2 * 4 + 2]);
        // (3,3) 距 (1,1) 为 sqrt(8) > 2，半径外
        assertEquals(0, costs[3 * 4 + 3]);
    }

    @Test
    void multipleWinnersTakeMaximumContribution() {
        int[] costs = InflationCalculator.inflate(
                region(0, 0, 5, 1),
                List.of(winner(1, 0, 40), winner(3, 0, 100)),
                2, 0.5);

        // (2,0) 距 cost40 障碍 1 格 → 20；距 cost100 障碍 1 格 → 50，取 50
        assertEquals(50, costs[2]);
        // (0,0) 只受 (1,0) 影响：20
        assertEquals(20, costs[0]);
        // (4,0) 只受 (3,0) 影响：50
        assertEquals(50, costs[4]);
    }

    @Test
    void zeroRadiusProducesNoInflation() {
        int[] costs = InflationCalculator.inflate(
                region(0, 0, 3, 3), List.of(winner(1, 1, 100)), 0, 0.5);

        for (int cost : costs) {
            assertEquals(0, cost);
        }
    }

    @Test
    void zeroCostWinnerProducesNoInflation() {
        int[] costs = InflationCalculator.inflate(
                region(0, 0, 3, 3), List.of(winner(1, 1, 0)), 2, 0.5);

        for (int cost : costs) {
            assertEquals(0, cost);
        }
    }

    @Test
    void clippedAtRegionBoundaryStillComputesInside() {
        // 障碍位于查询区域之外但在膨胀半径内，区域内仍应收到膨胀贡献
        int[] costs = InflationCalculator.inflate(
                region(2, 2, 4, 4), List.of(winner(1, 2, 100)), 2, 0.5);

        assertEquals(50, costs[0], "(2,2) 距区域外障碍 (1,2) 为 1 格");
    }
}
