package com.example.starter.survey.domain;

/**
 * 缺陷整改状态。
 */
public enum DefectStatus {
    /** 待整改/待复查。 */
    OPEN,
    /** 已在期限内整改并受理。 */
    RECTIFIED,
    /** 逾期提交，复查不予受理。 */
    REJECTED_LATE
}
