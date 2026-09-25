package com.example.starter.rebate.domain;

/**
 * 疑点状态：OPEN 待补充说明；EXPLAINED 已说明待复核；CLEARED 复核确认已解除。
 */
public enum DoubtStatus {
    OPEN,
    EXPLAINED,
    CLEARED
}
