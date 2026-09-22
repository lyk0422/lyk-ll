package com.example.starter.costmap.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DistanceTransform} 距离变换正确性测试。
 */
class DistanceTransformTest {

    private static boolean[] gridWithObstacle(int width, int height, int ox, int oy) {
        boolean[] g = new boolean[width * height];
        g[oy * width + ox] = true;
        return g;
    }

    @Test
    void singleObstacleEuclideanDistances() {
        int width = 5;
        int height = 5;
        double[] d = DistanceTransform.euclideanDistances(gridWithObstacle(width, height, 2, 2), width, height);
        assertEquals(0.0, d[2 * width + 2], 1e-9);
        // 横向、纵向相邻格距离 1
        assertEquals(1.0, d[2 * width + 1], 1e-9);
        assertEquals(1.0, d[3 * width + 2], 1e-9);
        // 对角格距离 sqrt(2)
        assertEquals(Math.sqrt(2), d[3 * width + 3], 1e-9);
        // 角格距离 sqrt(8)
        assertEquals(Math.sqrt(8), d[0], 1e-9);
        assertEquals(Math.sqrt(8), d[4 * width + 4], 1e-9);
    }

    @Test
    void multipleObstaclesTakeNearest() {
        int width = 4;
        int height = 1;
        boolean[] g = new boolean[width];
        g[0] = true;
        g[3] = true;
        double[] d = DistanceTransform.euclideanDistances(g, width, 1);
        assertEquals(0, d[0], 1e-9);
        assertEquals(1, d[1], 1e-9);
        assertEquals(1, d[2], 1e-9);
        assertEquals(0, d[3], 1e-9);
    }

    @Test
    void obstacleLineAcrossRows() {
        int width = 3;
        int height = 3;
        boolean[] g = new boolean[width * height];
        // 中间一整列是障碍
        for (int y = 0; y < height; y++) {
            g[y * width + 1] = true;
        }
        double[] d = DistanceTransform.euclideanDistances(g, width, height);
        assertEquals(1.0, d[0], 1e-9);
        assertEquals(0.0, d[1], 1e-9);
        assertEquals(1.0, d[2], 1e-9);
    }
}
