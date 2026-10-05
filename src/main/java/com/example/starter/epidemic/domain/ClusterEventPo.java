package com.example.starter.epidemic.domain;

import java.time.Instant;

/**
 * 聚集性疫情事件行模型：同一场所 72 小时（UTC）内 3 例及以上报告触发。
 *
 * <p>{@code openInvestigationId} 为当前进行中协查单编号，由条件 UPDATE 保证
 * 同一事件同一时刻仅一张进行中协查单；CLOSED 为终态。</p>
 */
public class ClusterEventPo {
    /** 聚集事件编号，自增主键。 */
    private long id;
    /** 暴露场所编码。 */
    private String siteCode;
    /** 事件状态。 */
    private ClusterStatus status;
    /** 窗口内首例报告时刻（UTC）。 */
    private Instant firstCaseAt;
    /** 窗口内末例报告时刻（UTC）。 */
    private Instant lastCaseAt;
    /** 当前进行中协查单编号；无则为 null。 */
    private Long openInvestigationId;
    /** 协查结论回写内容；未回写为 null。 */
    private String conclusion;
    /** 事件生成时刻（UTC）。 */
    private Instant createdAt;
    /** 关闭时刻（UTC）；未关闭为 null。 */
    private Instant closedAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public ClusterStatus getStatus() { return status; }
    public void setStatus(ClusterStatus status) { this.status = status; }
    public Instant getFirstCaseAt() { return firstCaseAt; }
    public void setFirstCaseAt(Instant firstCaseAt) { this.firstCaseAt = firstCaseAt; }
    public Instant getLastCaseAt() { return lastCaseAt; }
    public void setLastCaseAt(Instant lastCaseAt) { this.lastCaseAt = lastCaseAt; }
    public Long getOpenInvestigationId() { return openInvestigationId; }
    public void setOpenInvestigationId(Long openInvestigationId) { this.openInvestigationId = openInvestigationId; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
