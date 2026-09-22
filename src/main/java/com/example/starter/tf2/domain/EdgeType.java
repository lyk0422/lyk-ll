package com.example.starter.tf2.domain;

/**
 * 变换边的语义类型。
 * <ul>
 *   <li>STATIC：静态变换，全时刻有效，不参与插值与窗口淘汰；</li>
 *   <li>DYNAMIC：动态变换，带纳秒时间戳样本，按时间线性/slerp 插值，受缓存窗口约束。</li>
 * </ul>
 */
public enum EdgeType {
    STATIC,
    DYNAMIC
}
