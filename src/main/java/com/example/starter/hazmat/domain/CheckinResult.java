package com.example.starter.hazmat.domain;

/**
 * 打卡结果：ACCEPTED 正常受理；LATE_AFTER_CLOSE 结案后迟到，仅记录迟到事件。
 */
public enum CheckinResult {
    ACCEPTED,
    LATE_AFTER_CLOSE
}
