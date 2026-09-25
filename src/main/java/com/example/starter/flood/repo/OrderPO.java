package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 泄流指令 PO（下达→签收→执行→回执/升级）。
 */
public class OrderPO {
    public Long id;
    public String orderNo;
    public Long planId;
    public String status;
    public LocalDateTime issuedAt;
    public LocalDateTime signDeadline;
    public LocalDateTime signedAt;
    public String signer;
    public LocalDateTime escalatedAt;
    public String escalatedTo;
    public LocalDateTime executedAt;
    public String executor;
    public LocalDateTime receiptedAt;
    public String actualOpenings;
    public BigDecimal actualTotalRelease;
    public BigDecimal deviationRatio;
    public String deviationReason;
    public String receiptRequestId;
}
