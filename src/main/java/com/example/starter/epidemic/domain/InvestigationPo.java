package com.example.starter.epidemic.domain;

import java.time.Instant;

/**
 * 协查单行模型：聚集事件生成后推送相关机构；requestId 唯一保证幂等。
 */
public class InvestigationPo {
    /** 协查单编号，自增主键。 */
    private long id;
    /** 聚集事件编号。 */
    private long eventId;
    /** 幂等键。 */
    private String requestId;
    /** 协查推送机构编码。 */
    private String institutionCode;
    /** 协查说明；无则为 null。 */
    private String note;
    /** 协查单状态。 */
    private InvestigationStatus status;
    /** 协查结论；未办结为 null。 */
    private String conclusion;
    /** 创建时刻（UTC）。 */
    private Instant createdAt;
    /** 办结时刻（UTC）；未办结为 null。 */
    private Instant concludedAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getEventId() { return eventId; }
    public void setEventId(long eventId) { this.eventId = eventId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getInstitutionCode() { return institutionCode; }
    public void setInstitutionCode(String institutionCode) { this.institutionCode = institutionCode; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public InvestigationStatus getStatus() { return status; }
    public void setStatus(InvestigationStatus status) { this.status = status; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getConcludedAt() { return concludedAt; }
    public void setConcludedAt(Instant concludedAt) { this.concludedAt = concludedAt; }
}
