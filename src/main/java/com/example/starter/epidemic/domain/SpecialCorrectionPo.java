package com.example.starter.epidemic.domain;

import java.time.Instant;

/**
 * 归档后“特殊订正”流程行：须两名不同审核员先后确认方才生效，单人确认期间数据不变更。
 */
public class SpecialCorrectionPo {
    /** 自增主键。 */
    private Long id;
    /** 卡片编号。 */
    private String cardNo;
    /** 发起特殊订正的幂等键。 */
    private String requestId;
    /** 订正后病种编码。 */
    private String diseaseCode;
    /** 订正后病例类型：SUSPECTED 疑似 / CONFIRMED 确诊。 */
    private String caseType;
    /** 订正后诊断时刻（UTC）。 */
    private Instant diagnosisTime;
    /** 订正说明（疫情核实结论）。 */
    private String reason;
    /** 发起人。 */
    private String initiator;
    /** 第一名审核员。 */
    private String firstReviewer;
    /** 第一名确认时刻（UTC）。 */
    private Instant firstAt;
    /** 第二名审核员（必须与第一人不同）。 */
    private String secondReviewer;
    /** 第二名确认并生效时刻（UTC）。 */
    private Instant secondAt;
    /** 流程状态。 */
    private SpecialStatus status;
    /** 生效后生成的新版本号；未生效为 null。 */
    private Integer effectiveVersion;
    /** 创建时刻（UTC）。 */
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCardNo() { return cardNo; }
    public void setCardNo(String cardNo) { this.cardNo = cardNo; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getDiseaseCode() { return diseaseCode; }
    public void setDiseaseCode(String diseaseCode) { this.diseaseCode = diseaseCode; }
    public String getCaseType() { return caseType; }
    public void setCaseType(String caseType) { this.caseType = caseType; }
    public Instant getDiagnosisTime() { return diagnosisTime; }
    public void setDiagnosisTime(Instant diagnosisTime) { this.diagnosisTime = diagnosisTime; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getInitiator() { return initiator; }
    public void setInitiator(String initiator) { this.initiator = initiator; }
    public String getFirstReviewer() { return firstReviewer; }
    public void setFirstReviewer(String firstReviewer) { this.firstReviewer = firstReviewer; }
    public Instant getFirstAt() { return firstAt; }
    public void setFirstAt(Instant firstAt) { this.firstAt = firstAt; }
    public String getSecondReviewer() { return secondReviewer; }
    public void setSecondReviewer(String secondReviewer) { this.secondReviewer = secondReviewer; }
    public Instant getSecondAt() { return secondAt; }
    public void setSecondAt(Instant secondAt) { this.secondAt = secondAt; }
    public SpecialStatus getStatus() { return status; }
    public void setStatus(SpecialStatus status) { this.status = status; }
    public Integer getEffectiveVersion() { return effectiveVersion; }
    public void setEffectiveVersion(Integer effectiveVersion) { this.effectiveVersion = effectiveVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
