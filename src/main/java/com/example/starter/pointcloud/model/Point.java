package com.example.starter.pointcloud.model;

/**
 * 单个 LiDAR 点。
 *
 * @param x              X 坐标，单位米
 * @param y              Y 坐标，单位米
 * @param z              Z 坐标，单位米
 * @param timestampNanos 采集时间戳，纳秒，单调时钟语义；闭区间比较
 */
public record Point(double x, double y, double z, long timestampNanos) {
}
