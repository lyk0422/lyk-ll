package com.example.starter.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 批次审查主表 PO。一个批号全局唯一对应一个审查流程。
 */
public class Review {

    private Long id;
    private String enterpriseName;
    private String productName;
    private String batchNo;
    private ReviewStatus status;
    /** 当前已受理的资料目录代码，JSON 数组文本。 */
    private String documentsJson;
    /** 一次性告知的缺项资料代码，JSON 数组文本；空值表示无缺项告知。 */
    private String missingJson;
    private Instant declaredAt;
    /** 补正告知日期（Asia/Shanghai），空值表示未告知。 */
    private LocalDate correctionNoticeDate;
    /** 补正截止日期（告知后第 5 个工作日，恰当日提交仍受理）。 */
    private LocalDate correctionDeadline;
    private Instant correctedAt;
    /** 公示开始 UTC 日期。 */
    private LocalDate publicityStartUtc;
    /** 公示期满 UTC 日期（开始日 + 7 个自然日）。 */
    private LocalDate publicityEndUtc;
    /** 综合评定结论 PASS/FAIL，空值表示尚未评定。 */
    private String conclusion;
    private Instant issuedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEnterpriseName() {
        return enterpriseName;
    }

    public void setEnterpriseName(String enterpriseName) {
        this.enterpriseName = enterpriseName;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewStatus status) {
        this.status = status;
    }

    public String getDocumentsJson() {
        return documentsJson;
    }

    public void setDocumentsJson(String documentsJson) {
        this.documentsJson = documentsJson;
    }

    public String getMissingJson() {
        return missingJson;
    }

    public void setMissingJson(String missingJson) {
        this.missingJson = missingJson;
    }

    public Instant getDeclaredAt() {
        return declaredAt;
    }

    public void setDeclaredAt(Instant declaredAt) {
        this.declaredAt = declaredAt;
    }

    public LocalDate getCorrectionNoticeDate() {
        return correctionNoticeDate;
    }

    public void setCorrectionNoticeDate(LocalDate correctionNoticeDate) {
        this.correctionNoticeDate = correctionNoticeDate;
    }

    public LocalDate getCorrectionDeadline() {
        return correctionDeadline;
    }

    public void setCorrectionDeadline(LocalDate correctionDeadline) {
        this.correctionDeadline = correctionDeadline;
    }

    public Instant getCorrectedAt() {
        return correctedAt;
    }

    public void setCorrectedAt(Instant correctedAt) {
        this.correctedAt = correctedAt;
    }

    public LocalDate getPublicityStartUtc() {
        return publicityStartUtc;
    }

    public void setPublicityStartUtc(LocalDate publicityStartUtc) {
        this.publicityStartUtc = publicityStartUtc;
    }

    public LocalDate getPublicityEndUtc() {
        return publicityEndUtc;
    }

    public void setPublicityEndUtc(LocalDate publicityEndUtc) {
        this.publicityEndUtc = publicityEndUtc;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
