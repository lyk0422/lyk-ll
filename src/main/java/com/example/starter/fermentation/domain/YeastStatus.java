package com.example.starter.fermentation.domain;

/**
 * 合成酵母单元状态。
 */
public enum YeastStatus {

    /** 可用：可被受控批次原子绑定投酵（受代次与回收时限约束）。 */
    AVAILABLE,
    /** 使用中：已绑定某个正在主酵（占用中）的批次，未回收前不可再投酵。 */
    IN_USE,
    /** 已报废：回收不合格或回收失效，永久不可再投酵。 */
    SCRAPPED
}
