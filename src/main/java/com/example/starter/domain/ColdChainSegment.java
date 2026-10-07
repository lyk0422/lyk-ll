package com.example.starter.domain;

import java.time.Instant;

/**
 * 冷链运输片段 PO：按证据版本整体冻结，补录/更正写入新版本行，旧版本行不回改。
 */
public class ColdChainSegment {

    private Long id;
    private Long ledgerId;
    /** 所属证据版本号（与台账 version 对应）。 */
    private int version;
    /** 片段顺序号：从 1 起连续递增。 */
    private int seq;
    /** 片段起点 UTC 精确时刻（半开区间 [start,end) 起点）。 */
    private Instant startTs;
    /** 片段终点 UTC 精确时刻（正长度，读数闭区间含该端点）。 */
    private Instant endTs;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public Instant getStartTs() {
        return startTs;
    }

    public void setStartTs(Instant startTs) {
        this.startTs = startTs;
    }

    public Instant getEndTs() {
        return endTs;
    }

    public void setEndTs(Instant endTs) {
        this.endTs = endTs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
