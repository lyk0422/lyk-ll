package com.example.starter.lidar.model;

/**
 * 空间查询类型。
 */
public enum QueryType {
    /** 闭区间轴对齐包围盒查询。 */
    BOX,
    /** 球域半径查询：点到球心欧氏距离不大于 radius（球面边界包含）。 */
    RADIUS,
    /** 轨迹走廊：点到折线（各段圆柱并集 + 端点球帽）的距离不大于 width/2（边界包含）。 */
    CORRIDOR
}
