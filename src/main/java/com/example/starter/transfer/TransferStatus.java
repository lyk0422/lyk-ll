package com.example.starter.transfer;

/**
 * 转关单状态机：APPLIED(已申请) → IN_TRANSIT(核注后在途) → CLOSED(结案)；
 * APPLIED 可撤销为 CANCELLED；在途超 48 小时未核销转 EXCEPTION，
 * 企业异常期间其余在途单转 FROZEN；EXCEPTION/FROZEN 经海关复核解除后回到 IN_TRANSIT。
 */
public enum TransferStatus {
    APPLIED,
    IN_TRANSIT,
    FROZEN,
    EXCEPTION,
    CLOSED,
    CANCELLED
}
