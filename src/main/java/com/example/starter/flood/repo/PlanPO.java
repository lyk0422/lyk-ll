package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 泄流方案 PO；滚动演算泄流能力不足时追加新版本，旧版本置 SUPERSEDED 留痕可查。
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
    /** EFFECTIVE / SUPERSEDED / COMPLETED / ARCHIVED。 */
    public String status;
    /** 执行中唯一约束位：执行中为 pondId，否则为 null。 */
    public Long activeKey;
    /** 方案版本号，同一处置链内自 1 递增。 */
    public Integer versionNo;
    /** 触发本版本的滚动演算主键，人工制定版本为 null。 */
    public Long rollingId;
    public LocalDateTime createdAt;
    public LocalDateTime supersededAt;
    public LocalDateTime completedAt;
    public LocalDateTime archivedAt;
}
