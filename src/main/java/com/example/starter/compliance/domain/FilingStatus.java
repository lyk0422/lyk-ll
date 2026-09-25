package com.example.starter.compliance.domain;

/**
 * 量产备案状态：
 * ACTIVE 有效；REVIEW_WATCH 复审观察（标准更新后 90 个 UTC 自然日内）；
 * SUSPENDED 暂停（逾期未复审／复审不通过／被新版本取代）；CANCELLED 注销终态。
 */
public enum FilingStatus {
    ACTIVE,
    REVIEW_WATCH,
    SUSPENDED,
    CANCELLED
}
