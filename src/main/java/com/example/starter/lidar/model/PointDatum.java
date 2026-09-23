package com.example.starter.lidar.model;

/**
 * 单个点云记录；坐标与时间单位由调用方约定，服务内部不做换算。
 *
 * @param x X 坐标
 * @param y Y 坐标
 * @param z Z 坐标
 * @param t 采集时间戳（epoch 毫秒）
 */
public record PointDatum(double x, double y, double z, long t) {
}
