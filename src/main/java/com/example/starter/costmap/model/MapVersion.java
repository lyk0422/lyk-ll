package com.example.starter.costmap.model;

/**
 * 不可变地图版本：创建后静态栅格与膨胀参数均不可修改。
 *
 * @param versionId       版本ID
 * @param width           栅格列数
 * @param height          栅格行数
 * @param resolution      分辨率，米/格
 * @param inflationRadius 障碍物膨胀半径，米
 * @param costDecay        膨胀代价指数衰减系数，1/米
 * @param staticGrid      原始静态层代价（0..254），行优先
 * @param staticInflated  静态层一次预计算的膨胀结果（非每次更新重建），行优先
 * @param seq             已应用障碍物更新的逻辑序号
 */
public record MapVersion(
        String versionId,
        int width,
        int height,
        double resolution,
        double inflationRadius,
        double costDecay,
        byte[] staticGrid,
        byte[] staticInflated,
        long seq) {
}
