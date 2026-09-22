package com.example.starter.tf.domain;

/**
 * 坐标系边类型。
 */
public enum EdgeType {

    /** 静态变换：全时刻有效，每条边仅一个位姿。 */
    STATIC,

    /** 动态变换：按 epoch 纳秒时间戳采样，查询时在相邻样本间插值。 */
    DYNAMIC
}
