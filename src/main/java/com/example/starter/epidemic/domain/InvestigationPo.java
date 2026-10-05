package com.example.starter.epidemic.domain;

import java.time.Instant;
import java.util.List;

/**
 * 协查单行模型：聚集事件生成时自动推送相关机构；同一事件同时刻只允许一张进行中协查单。
 *
 * <p>{@code activeEventKey} 为进行中占位键（=事件编号），结案后置 NULL；数据库唯一约束
 * 保证同一聚集事件并发生成协查单时只有一张成功。</p>
 */
public class InvestigationPo {
    /** 自增主键（协查单号）。 */
    private Long id;
    /** 所属聚集事件编号。 */
    private String eventNo;
    /** 幂等键（事件自动生成时为 auto-事件编号）。 */
    private String requestId;
    /** 协查单状态。 */
    private InvestigationStatus status;
    /** 协查结论；未结案为 null。 */
    private Conclusion conclusion;
    /** 协查说明；无补充为 null。 */
    private String note;
    /** 发起人（自动生成为 system）。 */
    private String creator;
    /** 结案操作人；未结案为 null。 */
    private String operator;
    /** 被推送机构编码（相关机构）。 */
    private List<String> targets = List.of();
    /** 生成时刻（UTC）。 */
    private Instant createdAt;
    /** 结案时刻（UTC）；未结案为 null。 */
    private Instant concludedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventNo() { return eventNo; }
    public void setEventNo(String eventNo) { this.eventNo = eventNo; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public InvestigationStatus getStatus() { return status; }
    public void setStatus(InvestigationStatus status) { this.status = status; }
    public Conclusion getConclusion() { return conclusion; }
    public void setConclusion(Conclusion conclusion) { this.conclusion = conclusion; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getConcludedAt() { return concludedAt; }
    public void setConcludedAt(Instant concludedAt) { this.concludedAt = concludedAt; }
}
