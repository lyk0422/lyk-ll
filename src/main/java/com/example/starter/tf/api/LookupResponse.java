package com.example.starter.tf.api;

import java.util.List;

/**
 * 坐标变换查询结果。
 *
 * @param source       源坐标系
 * @param target       目标坐标系
 * @param stampNanos   查询时刻，epoch 纳秒
 * @param path         source 到 target 的完整坐标系链路（含两端）
 * @param transform    source 在 target 坐标系中的位姿
 * @param interpolated 链路上是否存在插值得到的动态边
 */
public record LookupResponse(String source,
                             String target,
                             long stampNanos,
                             List<String> path,
                             PoseDto transform,
                             boolean interpolated) {
}
