package com.example.starter.lc.domain;

/**
 * 幂等请求覆盖的业务操作类型。
 */
public enum OperationType {
    /** 受益人交单（首次）。 */
    PRESENT,
    /** 拒付后受益人改单重交，按新批次重新起算审单期。 */
    RESUBMIT,
    /** 银行发出一次性拒付通知。 */
    NOTICE,
    /** 开证申请人声明放弃不符点。 */
    WAIVER,
    /** 银行付款承兑。 */
    PAY,
    /** 拒付退单。 */
    REFUND
}
