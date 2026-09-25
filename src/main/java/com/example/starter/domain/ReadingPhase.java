package com.example.starter.domain;

/**
 * 温湿度数据所属业务阶段。
 */
public enum ReadingPhase {
    /** 日常上报 */
    DAILY,
    /** 告警采样 */
    ALARM,
    /** 一轮复测 */
    ROUND_1,
    /** 二轮复测 */
    ROUND_2
}
