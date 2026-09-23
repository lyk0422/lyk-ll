package com.example.starter.domain;

/**
 * 运输段状态。
 */
public enum SegmentStatus {

    /** 段仍打开，尚未交接关闭。 */
    OPEN,

    /** 合格：序号连续、间隔不超限、无超限或连续超限未超过允许分钟数。 */
    CLEAR,

    /** 温度偏离：存在连续超限严格大于最大允许连续超限分钟数。 */
    EXCURSION,

    /** 证据不完整：缺号、采样间隔超限或覆盖序号范围前后缺读数。 */
    INCOMPLETE
}
