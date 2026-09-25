package com.example.starter.lc.domain;

import java.time.LocalDateTime;

/**
 * 不符点。拒付通知必须一次性列明全部不符点，持久化后不得补充。
 */
public class Discrepancy {

    private Long id;
    /** 所属交单批次ID。 */
    private Long presentationId;
    /** 不符点所在单据类型，如 BILL_OF_LADING/COMMERCIAL_INVOICE。 */
    private String docType;
    /** 不符点代码。 */
    private String discrepancyCode;
    /** 不符点描述（合成）。 */
    private String description;
    /** 一次性通知内的排列序号。 */
    private Integer seqNo;
    private LocalDateTime createdAtUtc;

    public Discrepancy() {
    }

    public Discrepancy(String docType, String discrepancyCode, String description, Integer seqNo) {
        this.docType = docType;
        this.discrepancyCode = discrepancyCode;
        this.description = description;
        this.seqNo = seqNo;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPresentationId() {
        return presentationId;
    }

    public void setPresentationId(Long presentationId) {
        this.presentationId = presentationId;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getDiscrepancyCode() {
        return discrepancyCode;
    }

    public void setDiscrepancyCode(String discrepancyCode) {
        this.discrepancyCode = discrepancyCode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }

    public LocalDateTime getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(LocalDateTime createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }
}
