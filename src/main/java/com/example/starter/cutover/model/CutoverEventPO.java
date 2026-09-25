package com.example.starter.cutover.model;

import java.time.LocalDateTime;

/**
 * 割接事件/回报持久化对象，对应表 cutover_event。
 * 同时承担幂等键记录、迟到事件记录与回退判据命中记录。
 */
public class CutoverEventPO {
    private Long id;
    private String requestId;
    private String requestUid;
    private String eventType;
    private Integer seqNo;
    private String payloadFingerprint;
    private String resultSnapshot;
    private Boolean lateFlag;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestUid() { return requestUid; }
    public void setRequestUid(String requestUid) { this.requestUid = requestUid; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }
    public String getPayloadFingerprint() { return payloadFingerprint; }
    public void setPayloadFingerprint(String payloadFingerprint) { this.payloadFingerprint = payloadFingerprint; }
    public String getResultSnapshot() { return resultSnapshot; }
    public void setResultSnapshot(String resultSnapshot) { this.resultSnapshot = resultSnapshot; }
    public Boolean getLateFlag() { return lateFlag; }
    public void setLateFlag(Boolean lateFlag) { this.lateFlag = lateFlag; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
