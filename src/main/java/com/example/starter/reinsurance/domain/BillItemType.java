package com.example.starter.reinsurance.domain;

/**
 * 账单明细类型。
 */
public enum BillItemType {

    /** 保费分保：分出保费 × 分保比例。 */
    PREMIUM,

    /** 赔款摊回：已决赔款（或未决预提/结案冲销净额）× 分保比例，受保单责任限额约束。 */
    CLAIM_RECOVERY,

    /** 调整项：下一期调整单中的差异更正，引用原账单号。 */
    ADJUSTMENT
}
