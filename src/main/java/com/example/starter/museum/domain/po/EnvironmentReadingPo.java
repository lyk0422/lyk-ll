package com.example.starter.museum.domain.po;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 温湿度读数行。唯一约束 (showcaseId, sampleTime) 实现“展柜+采样时刻”去重；
 * late=TRUE 表示合同结案后到达，仅记录迟到事件，不参与判异。
 */
public class EnvironmentReadingPo {
    public Long id;
    public Long showcaseId;
    public Instant sampleTime;
    public BigDecimal temperature;
    public BigDecimal humidity;
    public Instant receivedAt;
    public Boolean late;
}
