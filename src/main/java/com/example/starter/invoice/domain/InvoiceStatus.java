package com.example.starter.invoice.domain;

/**
 * 发票状态：开具→交付→（可红冲）→归档；已全额红冲与已归档均为终态。
 */
public enum InvoiceStatus {
    /** 已开具，待交付 */
    ISSUED,
    /** 已交付，可红冲、可归档 */
    DELIVERED,
    /** 已全额红冲，终态，进一步红冲返回幂等结果 */
    RED_FLUSHED,
    /** 已归档，终态，仅允许红冲 */
    ARCHIVED
}
