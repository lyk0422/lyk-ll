package com.example.starter.ledger.domain;

/**
 * 站间调拨单状态。
 */
public enum TransferStatus {
    /** 已调出，在途。 */
    DISPATCHED,
    /** 调入方已签收。 */
    RECEIVED,
    /** 在途超时异常。 */
    EXCEPTION,
    /** 已人工核销（终态）。 */
    WRITTEN_OFF
}
