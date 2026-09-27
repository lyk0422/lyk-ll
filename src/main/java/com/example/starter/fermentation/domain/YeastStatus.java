package com.example.starter.fermentation.domain;

/**
 * 合成酵母单元状态。
 *
 * <p>状态流转：登记即 AVAILABLE；受控投酵原子绑定后置 IN_USE；
 * 回收合格回到 AVAILABLE（代次加 1），回收不合格置 SCRAPPED（终态）；
 * 可用单元距最近回收超过 72 小时未投酵则置 EXPIRED（终态）。
 */
public enum YeastStatus {

    /** 可用：代次不超过 5 且未失效时可投酵。 */
    AVAILABLE,
    /** 已被一个正在主酵的批次占用，其他批次不可再选。 */
    IN_USE,
    /** 已报废：回收活率不足或检出污染，不可再投酵。 */
    SCRAPPED,
    /** 已失效：可用单元距最近回收时刻超过 72 小时，不可再投酵。 */
    EXPIRED
}
