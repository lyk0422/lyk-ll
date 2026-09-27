package com.example.starter.damage.domain;

/**
 * 密封询价版本状态。
 * OPEN → OPENED（有中标）/ NO_AWARD（零有效报价）→ CONVERTED（中标转批复后冻结）。
 */
public enum InquiryStatus {
    /** 投标中：now 在 UTC 截止时刻之前可提交或修订报价 */
    OPEN,
    /** 已开标且存在中标报价，待双重复核 */
    OPENED,
    /** 已开标但零有效报价，不开批复，可发布下一序号询价 */
    NO_AWARD,
    /** 中标报价已经双重复核转为修理批复，询价/报价/资格版本全部冻结 */
    CONVERTED
}
