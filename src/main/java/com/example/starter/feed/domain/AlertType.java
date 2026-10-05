package com.example.starter.feed.domain;

/**
 * 合规告警类型。
 */
public enum AlertType {
    /** 出栏后迟到的含药饲喂记录。 */
    LATE_MEDICATED_FEEDING,
    /** 出栏核销后迟到的治疗性用药直报。 */
    LATE_TREATMENT_REPORT
}
