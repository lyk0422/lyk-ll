package com.example.starter.reinsurance.domain;

/**
 * 账单状态。
 */
public enum BillStatus {

    /** 待确认：账单生成后的初始状态，再保人可确认或异议。 */
    PENDING,

    /** 有异议：再保人逐项提出差异后进入；封存前不得再确认。 */
    DISPUTED,

    /** 双方确认并封存（终态）：异议与修改一律拒绝，差错走下一期调整单。 */
    SEALED
}
