package com.example.starter.costmap.cost;

import com.example.starter.costmap.domain.ObstacleRow;
import com.example.starter.costmap.domain.Region;

import java.util.List;

/**
 * 膨胀层计算（Nav2 风格的指数衰减简化版）。
 *
 * <p>对每个获胜障碍单元，距它欧氏距离 d（格）的位置贡献膨胀代价
 * {@code floor(winnerCost * costDecay^d)}（{@code 1 <= d <= radiusCells}），
 * 半径外贡献为 0；多个障碍的膨胀贡献取最大值。障碍格自身（d=0）的代价由动态层
 * 直接给出，膨胀层不重复贡献。</p>
 *
 * <p>调用方必须保证 {@code winners} 覆盖目标区域向外扩张 radiusCells 的包围盒，
 * 否则边界处的膨胀贡献会被截断（Service 层按此约定取数）。</p>
 */
public final class InflationCalculator {

    private InflationCalculator() {
    }

    /**
     * 在 region 内计算每格膨胀代价，返回与 region 行主序对齐的数组。
     */
    public static int[] inflate(Region region, List<ObstacleRow> winners,
                                int radiusCells, double costDecay) {
        int width = region.width();
        int height = region.height();
        int[] out = new int[region.cellCount()];
        if (radiusCells <= 0 || winners.isEmpty() || costDecay <= 0.0) {
            return out;
        }
        double radiusSquared = (double) radiusCells * radiusCells;
        for (ObstacleRow winner : winners) {
            int ox = winner.x();
            int oy = winner.y();
            int baseCost = winner.cost();
            if (baseCost <= 0) {
                continue;
            }
            // 障碍格在目标区域内的行列包围范围（扩张半径）
            int minX = Math.max(region.x0(), ox - radiusCells);
            int maxX = Math.min(region.x1() - 1, ox + radiusCells);
            int minY = Math.max(region.y0(), oy - radiusCells);
            int maxY = Math.min(region.y1() - 1, oy + radiusCells);
            for (int y = minY; y <= maxY; y++) {
                double dy = y - oy;
                for (int x = minX; x <= maxX; x++) {
                    double dx = x - ox;
                    if (dx == 0.0 && dy == 0.0) {
                        // 障碍格本身由动态层提供代价
                        continue;
                    }
                    double distSquared = dx * dx + dy * dy;
                    if (distSquared > radiusSquared) {
                        continue;
                    }
                    int contribution = (int) Math.floor(
                            baseCost * Math.pow(costDecay, Math.sqrt(distSquared)));
                    if (contribution <= 0) {
                        continue;
                    }
                    int idx = (y - region.y0()) * width + (x - region.x0());
                    if (contribution > out[idx]) {
                        out[idx] = contribution;
                    }
                }
            }
        }
        return out;
    }
}
