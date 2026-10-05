package com.example.starter.epidemic.domain;

import java.time.Instant;

/**
 * 聚集事件行模型：同一场所 72 小时（UTC）内 3 例及以上报告触发，关闭为终态。
 *
 * <p>{@code openKey} 为未关闭占位键（=场所编码），关闭后置 NULL；数据库唯一约束保证
 * 同一场所同时刻只有一个未关闭事件，并发归并只生成一个事件。</p>
 */
public class ClusterEventPo {
    /** 自增主键。 */
    private Long id;
    /** 聚集事件编号（业务主键）。 */
    private String eventNo;
    /** 暴露场所编码。 */
    private String siteCode;
    /** 事件状态。 */
    private ClusterStatus status;
    /** 首例报告时刻（UTC）。 */
    private Instant firstCaseAt;
    /** 末例报告时刻（UTC），随新卡归入推进。 */
    private Instant lastCaseAt;
    /** 涉及报告卡数量。 */
    private int cardCount;
    /** 事件生成时刻（UTC）。 */
    private Instant createdAt;
    /** 关闭时刻（UTC）；未关闭为 null。 */
    private Instant closedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventNo() { return eventNo; }
    public void setEventNo(String eventNo) { this.eventNo = eventNo; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
    public ClusterStatus getStatus() { return status; }
    public void setStatus(ClusterStatus status) { this.status = status; }
    public Instant getFirstCaseAt() { return firstCaseAt; }
    public void setFirstCaseAt(Instant firstCaseAt) { this.firstCaseAt = firstCaseAt; }
    public Instant getLastCaseAt() { return lastCaseAt; }
    public void setLastCaseAt(Instant lastCaseAt) { this.lastCaseAt = lastCaseAt; }
    public int getCardCount() { return cardCount; }
    public void setCardCount(int cardCount) { this.cardCount = cardCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
