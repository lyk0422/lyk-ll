package com.example.starter.quarantine;

/**
 * 预警事件处理状态（处理闭环）。
 */
public enum AlertStatus {

    /** 待处理：预警已生成，尚未闭环。 */
    OPEN,
    /** 已闭环：预警已处理结案。 */
    CLOSED
}
