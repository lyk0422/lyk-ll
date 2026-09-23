package com.example.starter.lidar.model;

/**
 * 查询命中的单个点。
 *
 * @param x          X 坐标
 * @param y          Y 坐标
 * @param z          Z 坐标
 * @param t          采集时间戳（epoch 毫秒）
 * @param chunkId    所属块 ID
 * @param version    实际读取块的版本
 * @param source     实际读取块的来源
 * @param pointIndex 块内点序（从 0 开始，按写入顺序）
 */
public record QueryPoint(
        double x,
        double y,
        double z,
        long t,
        String chunkId,
        long version,
        String source,
        int pointIndex
) {
}
