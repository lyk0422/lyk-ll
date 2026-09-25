package com.example.starter.margin.domain;

/**
 * 单笔负债状态。
 */
public enum LiabilityStatus {
    /** 未偿还（强平排序对象） */
    OUTSTANDING,
    /** 已偿还（强平处置或正常还款） */
    REPAID
}
