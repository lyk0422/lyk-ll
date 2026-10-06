package com.example.starter.domain;

/**
 * 调光触发来源。
 */
public enum DimSource {
    /** 人工调光。 */
    MANUAL,
    /** 车流量合成检测数据触发。 */
    TRAFFIC,
    /** 洞外亮度低于合成阈值触发。 */
    BRIGHTNESS,
    /** 事故告警触发。 */
    ACCIDENT
}
