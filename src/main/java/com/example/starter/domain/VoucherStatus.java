package com.example.starter.domain;

/**
 * 凭证状态机。
 * DRAFT → EFFECTIVE → SEALED（终态）；EFFECTIVE 可临时进入 DISPUTED，争议解决后以调整凭证闭环；
 * 被调整凭证引用的原凭证进入 ADJUSTED（终态）。
 */
public enum VoucherStatus {
    /** 待确认：单方或双方均未确认。 */
    DRAFT,
    /** 已生效：双方先后确认一致。 */
    EFFECTIVE,
    /** 争议挂起：争议期内发起争议且受理。 */
    DISPUTED,
    /** 已封账：争议期满终态，只读。 */
    SEALED,
    /** 已被双方签认的调整凭证调整，终态只读。 */
    ADJUSTED
}
