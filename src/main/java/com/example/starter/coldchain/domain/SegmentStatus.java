package com.example.starter.coldchain.domain;

/**
 * 运输段状态。
 */
public enum SegmentStatus {

    /** 开放中：运输段尚未交接关闭。 */
    OPEN,
    /** 合格：段内无缺号、无超间隔、无超时连续超限。 */
    CLEAR,
    /** 温度偏离：存在超过允许分钟数的连续超限区间。 */
    EXCURSION,
    /** 不完整：存在缺号或相邻采样间隔超限，不能判作合格。 */
    INCOMPLETE
}
