package com.example.starter.costmap.persistence;

/**
 * costmap 表行对象（含静态格栅与当前动态视图缓存）。
 *
 * <p>代价数组为行优先 {@code int[width*height]}，winner 数组为行优先
 * {@code long[width*height]}（每格获胜语句序号，0 表示无动态声明）。</p>
 */
public record CostmapPo(
        String id,
        int width,
        int height,
        double resolution,
        double originX,
        double originY,
        double inscribedRadius,
        double inflationRadius,
        double costScalingFactor,
        int[] staticCells,
        int[] liveCells,
        long[] winnerCells,
        long liveSeq,
        long allocSeq,
        long snapshotSeq,
        long createdAt) {
}
