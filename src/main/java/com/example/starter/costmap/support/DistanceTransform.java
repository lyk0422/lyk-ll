package com.example.starter.costmap.support;

/**
 * 二维栅格平方欧氏距离变换（Felzenszwalb-Huttenlocher 一维两遍算法），
 * 用于地图创建时一次性预计算静态层膨胀，时间 O(width*height)。
 */
public final class DistanceTransform {

    private DistanceTransform() {
    }

    private static final double INF = 1e18;

    /**
     * 计算每个格点到最近障碍格点的欧氏距离（单位格）。
     *
     * @param obstacles 行优先布尔数组，true 表示障碍格点（距离 0）
     */
    public static double[] euclideanDistances(boolean[] obstacles, int width, int height) {
        double[] d = new double[width * height];
        for (int i = 0; i < d.length; i++) {
            d[i] = obstacles[i] ? 0 : INF;
        }
        int n = Math.max(width, height);
        int[] v = new int[n + 1];
        double[] z = new double[n + 2];
        double[] f = new double[n];

        // 沿 y 方向（列）做一维平方距离变换
        double[] vertical = new double[width * height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                f[y] = d[y * width + x];
            }
            dt1d(f, height, v, z);
            for (int y = 0; y < height; y++) {
                vertical[y * width + x] = f[y];
            }
        }
        // 沿 x 方向（行）再做一遍并开平方
        double[] out = new double[width * height];
        for (int y = 0; y < height; y++) {
            int base = y * width;
            for (int x = 0; x < width; x++) {
                f[x] = vertical[base + x];
            }
            dt1d(f, width, v, z);
            for (int x = 0; x < width; x++) {
                out[base + x] = Math.sqrt(Math.max(0, f[x]));
            }
        }
        return out;
    }

    /**
     * 一维平方距离变换，原地输出：f[q] = min_p (f[p] + (q-p)^2)。
     */
    private static void dt1d(double[] f, int n, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k]))
                    / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k]))
                        / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        double[] result = new double[n];
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            long delta = q - v[k];
            result[q] = f[v[k]] + delta * delta;
        }
        System.arraycopy(result, 0, f, 0, n);
    }
}
