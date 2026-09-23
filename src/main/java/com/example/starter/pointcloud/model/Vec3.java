package com.example.starter.pointcloud.model;

/**
 * 三维点（不含时间），用于查询几何体定义。
 *
 * @param x X 坐标，单位米
 * @param y Y 坐标，单位米
 * @param z Z 坐标，单位米
 */
public record Vec3(double x, double y, double z) {
}
