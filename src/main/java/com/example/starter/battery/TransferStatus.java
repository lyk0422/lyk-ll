package com.example.starter.battery;

/**
 * 站间调拨单状态。
 */
public enum TransferStatus {
    /** 在途。 */
    IN_TRANSIT,
    /** 已签收。 */
    SIGNED,
    /** 调拨异常（在途超 24 小时）。 */
    EXCEPTION,
    /** 异常已人工核销（仅可核销一次）。 */
    WRITTEN_OFF
}
