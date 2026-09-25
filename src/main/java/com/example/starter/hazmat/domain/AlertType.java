package com.example.starter.hazmat.domain;

/**
 * 告警类型：DEVIATION 偏离预警（途经点超时未打卡）；LOST 失联（连续 2 点超时）；
 * ORDER_VIOLATION 打卡逆序拒绝；LATE_AFTER_CLOSE 结案后迟到打卡。
 */
public enum AlertType {
    DEVIATION,
    LOST,
    ORDER_VIOLATION,
    LATE_AFTER_CLOSE
}
