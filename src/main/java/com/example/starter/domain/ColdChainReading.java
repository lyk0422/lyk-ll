package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冷链温度读数 PO：随片段按版本冻结；缺样本只记缺口，绝不按零度补齐。
 */
public class ColdChainReading {

    private Long id;
    private Long segmentId;
    /** 读数顺序号：片内从 1 起连续递增。 */
    private int seq;
    /** 读数精确时刻（UTC，须落在片段闭区间内且不晚于提交时刻）。 */
    private Instant atTs;
    /** 合成温度读数（单位：摄氏度，合格闭区间 [2,8]，恰边界合格）。 */
    private BigDecimal tempCelsius;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(Long segmentId) {
        this.segmentId = segmentId;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public Instant getAtTs() {
        return atTs;
    }

    public void setAtTs(Instant atTs) {
        this.atTs = atTs;
    }

    public BigDecimal getTempCelsius() {
        return tempCelsius;
    }

    public void setTempCelsius(BigDecimal tempCelsius) {
        this.tempCelsius = tempCelsius;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
