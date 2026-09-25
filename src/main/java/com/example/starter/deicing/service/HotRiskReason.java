package com.example.starter.deicing.service;

/**
 * HOT 风险的具体成因，用于接口提示。
 */
public enum HotRiskReason {

    /** 车位被前序作业占用，最早可完成时刻已晚于计划起飞时刻。 */
    LATE_COMPLETION,

    /** 两步法防冰液在计划起飞时刻前失效，且起飞时刻前无可用重排窗口。 */
    HOT_EXPIRY_RISK
}
