package com.example.starter.domain;

/**
 * 策略下发前预演的仲裁结论。
 */
public enum RehearsalVerdict {
    /** 放行：能耗预估不超过当日剩余预算（恰等允许），且与进行中处置兼容。 */
    PASS,
    /** 挂起：与同分区进行中告警处置不兼容，等待处置完结，不产生任何副作用。 */
    SUSPENDED,
    /** 拒绝：预估能耗超过当日剩余预算，直接拒绝下发。 */
    REJECTED
}
