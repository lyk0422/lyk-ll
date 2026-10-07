package com.example.starter.domain;

import java.time.Instant;

/**
 * 冷链运输片段 PO。每版本 1 至 5 个顺序片段，[start,end) 正长度且相邻端点完全相接；
 * 更正后旧版本片段冻结保留，可复算。
 */
public class ColdChainSegment {

    private Long id;
    private Long ledgerId;
    /** 所属台账版本。 */
    private int ledgerVersion;
    /** 片段序号：每版本内从 1 连续递增。 */
    private int segmentSeq;
    /** 片段起点 UTC 精确时刻（含）。 */
    private Instant startUtc;
    /** 片段终点 UTC 精确时刻（片段区间本身不含，端点读数可取该时刻）。 */
    private Instant endUtc;
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

    public int getLedgerVersion() {
        return ledgerVersion;
    }

    public void setLedgerVersion(int ledgerVersion) {
        this.ledgerVersion = ledgerVersion;
    }

    public int getSegmentSeq() {
        return segmentSeq;
    }

    public void setSegmentSeq(int segmentSeq) {
        this.segmentSeq = segmentSeq;
    }

    public Instant getStartUtc() {
        return startUtc;
    }

    public void setStartUtc(Instant startUtc) {
        this.startUtc = startUtc;
    }

    public Instant getEndUtc() {
        return endUtc;
    }

    public void setEndUtc(Instant endUtc) {
        this.endUtc = endUtc;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
