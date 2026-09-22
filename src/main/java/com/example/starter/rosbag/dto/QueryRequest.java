package com.example.starter.rosbag.dto;

/**
 * 组合查询条件；所有边界均可空（null 表示不限制），均为闭区间。
 */
public record QueryRequest(
        String topic,
        Long fromNs,
        Long toNs,
        Long fromSeq,
        Long toSeq
) {
}
