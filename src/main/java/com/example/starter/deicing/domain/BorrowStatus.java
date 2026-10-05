package com.example.starter.deicing.domain;

/**
 * 跨班组借用单状态。
 */
public enum BorrowStatus {

    /** 未归还：计入库存台账借出量。 */
    OPEN,

    /** 已归还：归还登记完成，不再计入借出量。 */
    RETURNED
}
