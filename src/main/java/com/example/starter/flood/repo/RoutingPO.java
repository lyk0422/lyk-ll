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
    public String inputSnapshot;
    public String criterion;
    public LocalDateTime createdAt;
}
