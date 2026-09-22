package com.example.starter.tf.domain;

import com.example.starter.tf.math.Transform3d;

import java.util.List;

/**
 * 一次坐标链路查询的领域结果。
 *
 * @param path         source 到 target 的完整坐标系链路（含两端）
 * @param transform    source 在 target 坐标系中的位姿
 * @param interpolated 链路上是否至少有一条动态边发生了样本间插值
 */
public record LookupResult(List<String> path, Transform3d transform, boolean interpolated) {
}
