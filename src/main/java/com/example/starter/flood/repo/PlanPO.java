package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 泄流方案 PO。
 */
public class PlanPO {
    public Long id;
    public String planNo;
    public Long pondId;
    public Long warningId;
    public Long routingId;
    public String requestId;
    public String openings;
    public BigDecimal totalRelease;
    public BigDecimal downstreamSafeRelease;
    /** EFFECTIVE / COMPLETED / ARCHIVED。 */
    public String status;
    /** 执行中唯一约束位：执行中为 pondId，否则为 null。 */
    public Long activeKey;
    public LocalDateTime createdAt;
    public LocalDateTime completedAt;
    public LocalDateTime archivedAt;
}
