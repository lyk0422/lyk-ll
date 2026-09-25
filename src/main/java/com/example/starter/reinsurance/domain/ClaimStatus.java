package com.example.starter.reinsurance.domain;

/**
 * 赔案状态。
 */
public enum ClaimStatus {

    /** 未决（估损可调整，按估损金额预提摊回）。 */
    OPEN,

    /** 最终结案（终态）：仅允许一次最终结案，迟到估损调整只记事件。 */
    FINAL_CLOSED
}
