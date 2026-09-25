package com.example.starter.lc.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 交单批次。改单重交生成新批次并重新起算审单期。所有时间字段为 UTC。
 */
public class PresentationBatch {

    private Long id;
    /** 交单批次业务编号。 */
    private String presentationNo;
    private String lcNo;
    /** 批次序号：首次交单=1，改单重交逐次加1。 */
    private Integer batchSeq;
    /** 改单重交时被取代的上一批次ID，首交为空。 */
    private Long supersedesId;
    private PresentationStatus status;
    /** 本批次索偿/付款金额。 */
    private BigDecimal drawAmount;
    /** 交单单据清单JSON（合成数据）。 */
    private String docsJson;
    /** 银行收到单据时刻(UTC)，审单期起算点。 */
    private LocalDateTime receivedAtUtc;
    /** 审单截止：收到次日起第5个银行工作日17:00(UTC)。 */
    private LocalDateTime deadlineUtc;
    /** 拒付通知发出时刻(UTC)，未发为空。 */
    private LocalDateTime noticeAtUtc;
    /** 接受（含放弃不符点/视为接受）时刻(UTC)。 */
    private LocalDateTime acceptedAtUtc;
    /** 实际付款时刻(UTC)，终态凭据。 */
    private LocalDateTime paidAtUtc;
    /** 拒付退单时刻(UTC)。 */
    private LocalDateTime refundedAtUtc;
    /** 审单中占位值=lc_account.id；离开UNDER_REVIEW置空。 */
    private Long activeSlot;
    private LocalDateTime createdAtUtc;
    private LocalDateTime updatedAtUtc;

    /** 一次性列明的全部不符点（持久化在 discrepancy 表）。 */
    private List<Discrepancy> discrepancies = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPresentationNo() {
        return presentationNo;
    }

    public void setPresentationNo(String presentationNo) {
        this.presentationNo = presentationNo;
    }

    public String getLcNo() {
        return lcNo;
    }

    public void setLcNo(String lcNo) {
        this.lcNo = lcNo;
    }

    public Integer getBatchSeq() {
        return batchSeq;
    }

    public void setBatchSeq(Integer batchSeq) {
        this.batchSeq = batchSeq;
    }

    public Long getSupersedesId() {
        return supersedesId;
    }

    public void setSupersedesId(Long supersedesId) {
        this.supersedesId = supersedesId;
    }

    public PresentationStatus getStatus() {
        return status;
    }

    public void setStatus(PresentationStatus status) {
        this.status = status;
    }

    public BigDecimal getDrawAmount() {
        return drawAmount;
    }

    public void setDrawAmount(BigDecimal drawAmount) {
        this.drawAmount = drawAmount;
    }

    public String getDocsJson() {
        return docsJson;
    }

    public void setDocsJson(String docsJson) {
        this.docsJson = docsJson;
    }

    public LocalDateTime getReceivedAtUtc() {
        return receivedAtUtc;
    }

    public void setReceivedAtUtc(LocalDateTime receivedAtUtc) {
        this.receivedAtUtc = receivedAtUtc;
    }

    public LocalDateTime getDeadlineUtc() {
        return deadlineUtc;
    }

    public void setDeadlineUtc(LocalDateTime deadlineUtc) {
        this.deadlineUtc = deadlineUtc;
    }

    public LocalDateTime getNoticeAtUtc() {
        return noticeAtUtc;
    }

    public void setNoticeAtUtc(LocalDateTime noticeAtUtc) {
        this.noticeAtUtc = noticeAtUtc;
    }

    public LocalDateTime getAcceptedAtUtc() {
        return acceptedAtUtc;
    }

    public void setAcceptedAtUtc(LocalDateTime acceptedAtUtc) {
        this.acceptedAtUtc = acceptedAtUtc;
    }

    public LocalDateTime getPaidAtUtc() {
        return paidAtUtc;
    }

    public void setPaidAtUtc(LocalDateTime paidAtUtc) {
        this.paidAtUtc = paidAtUtc;
    }

    public LocalDateTime getRefundedAtUtc() {
        return refundedAtUtc;
    }

    public void setRefundedAtUtc(LocalDateTime refundedAtUtc) {
        this.refundedAtUtc = refundedAtUtc;
    }

    public Long getActiveSlot() {
        return activeSlot;
    }

    public void setActiveSlot(Long activeSlot) {
        this.activeSlot = activeSlot;
    }

    public LocalDateTime getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(LocalDateTime createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    public LocalDateTime getUpdatedAtUtc() {
        return updatedAtUtc;
    }

    public void setUpdatedAtUtc(LocalDateTime updatedAtUtc) {
        this.updatedAtUtc = updatedAtUtc;
    }

    public List<Discrepancy> getDiscrepancies() {
        return discrepancies;
    }

    public void setDiscrepancies(List<Discrepancy> discrepancies) {
        this.discrepancies = discrepancies;
    }
}
