package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入库洪水过程线点 PO：处置链维度，预报时刻唯一。
 */
public class HydrographPointPO {
    public Long id;
    public Long warningId;
    /** 预报时刻（时区 Asia/Shanghai）。 */
    public LocalDateTime forecastTime;
    /** 该时刻入库流量，立方米每秒。 */
    public BigDecimal inflowRate;
    public String requestId;
    public LocalDateTime createdAt;
}
