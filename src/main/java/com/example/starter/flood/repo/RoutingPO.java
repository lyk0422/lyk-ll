package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调洪演算 PO，含输入快照与判定口径。
 */
public class RoutingPO {
    public Long id;
    public String routingNo;
    public Long pondId;
    public Long warningId;
    public String requestId;
    public BigDecimal currentWaterLevel;
    public BigDecimal currentStorage;
    public BigDecimal maxStorage;
    public BigDecimal availableCapacity;
    public BigDecimal designFloodVolume;
    public String capacityJudgment;
    public BigDecimal dryBeachLength;
    public BigDecimal phreaticDepth;
    public String dryBeachJudgment;
    public String phreaticJudgment;
    public String dangerJudgment;
    /** 演算版本号：同一处置链内自 1 递增。 */
    public Integer version;
    /** MANUAL 静态演算 / ROLLING 滚动重算。 */
    public String triggerType;
    /** 入库洪水过程线 JSON，仅滚动版本有值。 */
    public String inflowHydrograph;
    /** 滚动重算的上一版本演算主键。 */
    public Long prevRoutingId;
    public String inputSnapshot;
    public String criterion;
    public LocalDateTime createdAt;
}
