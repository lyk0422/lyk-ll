package com.example.starter.epidemic.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 报告卡主表行模型：一张有效卡对应一行，随最新版本与终态更新。
 *
 * <p>{@code dedupKey} 为“患者＋病种＋发病日期”去重指纹，有效卡唯一；撤回终态时置空
 * （唯一索引允许多个 NULL），从而由数据库保证并发重复报告只有一张有效卡。</p>
 */
public class CardPo {
    /** 卡片编号，业务主键，全局唯一。 */
    private String cardNo;
    /** 患者证件号（合成数据），去重维度之一。 */
    private String patientId;
    /** 患者姓名。 */
    private String patientName;
    /** 当前病种编码，去重维度之一。 */
    private String diseaseCode;
    /** 发病日期（无时区日期，UTC 口径记录），去重维度之一。 */
    private LocalDate onsetDate;
    /** 报告医疗机构编码。 */
    private String institutionCode;
    /** 当前最新版本号，初报为 1。 */
    private int currentVersion;
    /** 卡片状态。 */
    private CardStatus status;
    /** 有效卡去重指纹；撤回后为 null。 */
    private String dedupKey;
    /** 创建时刻（UTC）。 */
    private Instant createdAt;
    /** 最近更新时刻（UTC）。 */
    private Instant updatedAt;

    public String getCardNo() { return cardNo; }
    public void setCardNo(String cardNo) { this.cardNo = cardNo; }
    public String getPatientId() { return patientId; }
    public void setPatientId(String patientId) { this.patientId = patientId; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }
    public String getDiseaseCode() { return diseaseCode; }
    public void setDiseaseCode(String diseaseCode) { this.diseaseCode = diseaseCode; }
    public LocalDate getOnsetDate() { return onsetDate; }
    public void setOnsetDate(LocalDate onsetDate) { this.onsetDate = onsetDate; }
    public String getInstitutionCode() { return institutionCode; }
    public void setInstitutionCode(String institutionCode) { this.institutionCode = institutionCode; }
    public int getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(int currentVersion) { this.currentVersion = currentVersion; }
    public CardStatus getStatus() { return status; }
    public void setStatus(CardStatus status) { this.status = status; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
