package com.example.starter.fishery.domain;

/**
 * 渔船航次状态，流转：DEPARTED（出港申报受理）→ DISCHARGED（进港报告完成）→ CLOSED（配额核销结案）；
 * 进港偏差或兼捕触发人工复核时为 IN_REVIEW，复核结论后回到 DISCHARGED。
 */
public enum TripStatus {
    /** 出港申报已受理，作业中。 */
    DEPARTED,
    /** 进港报告已提交，等待配额核销（无未决复核）。 */
    DISCHARGED,
    /** 人工复核中，该船配额余额冻结。 */
    IN_REVIEW,
    /** 终态：配额核销完成，航次结案。 */
    CLOSED
}
