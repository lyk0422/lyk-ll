package com.example.starter.lc;

/**
 * requestId 幂等记录覆盖的操作类型。
 */
public enum OperationType {

    /** 开立信用证。 */
    CREATE_LC,

    /** 受益人交单（含改单重交的新批次）。 */
    PRESENT,

    /** 银行发出拒付通知。 */
    REFUSE,

    /** 开证申请人放弃不符点。 */
    WAIVE,

    /** 银行付款。 */
    PAY
}
