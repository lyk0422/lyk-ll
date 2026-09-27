package com.example.starter.damage.domain;

/**
 * 密封询价版本状态。
 */
public enum InquiryStatus {
    /** 投标中：now 小于截止时刻可提交/修订报价 */
    OPEN,
    /** 已开标：全部当前报价已按开标时刻资格状态冻结，重复开标返回首次清单 */
    OPENED,
    /** 已双人复核转修理批复：询价、报价与资格版本全部冻结 */
    FROZEN,
    /** 已被新询价版本取代 */
    SUPERSEDED
}
