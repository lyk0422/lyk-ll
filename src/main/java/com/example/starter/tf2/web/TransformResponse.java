package com.example.starter.tf2.web;

import java.util.List;

/**
 * 坐标变换查询结果。
 *
 * @param sourceFrame      查询起点坐标系
 * @param targetFrame      查询终点坐标系
 * @param stampNanos       查询时刻（纳秒）
 * @param path             实际使用的坐标系链路
 * @param tx,ty,tz         组合后平移（米）
 * @param qx,qy,qz,qw      组合后旋转四元数
 */
public record TransformResponse(
        String sourceFrame,
        String targetFrame,
        long stampNanos,
        List<String> path,
        double tx,
        double ty,
        double tz,
        double qx,
        double qy,
        double qz,
        double qw
) {
}
