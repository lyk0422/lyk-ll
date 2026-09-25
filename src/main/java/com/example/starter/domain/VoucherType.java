package com.example.starter.domain;

/** 凭证类型。 */
public enum VoucherType {
    /** 正常日交接凭证。 */
    NORMAL,
    /** 双方签认的差错调整凭证，引用原凭证号。 */
    ADJUSTMENT
}
