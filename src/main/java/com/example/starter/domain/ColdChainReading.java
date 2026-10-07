package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 冷链温度读数 PO。片内递增序号 + UTC 精确时刻 + 温度（摄氏度）；
 * 合成合格规则为闭区间 [2,8]，恰边界合格，任一点越界即不通过。
 */
public class ColdChainReading {

    private Long id;
    private Long ledgerId;
    /** 所属台账版本。 */
    private int ledgerVersion;
    /** 所属片段序号。 */
    private int segmentSeq;
    /** 片内读数序号（递增，必填）。 */
    private int readingSeq;
    /** 读数采样 UTC 精确时刻。 */
    private Instant readAt;
    /** 温度（摄氏度）。 */
    private BigDecimal temperature;
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

    public int getReadingSeq() {
        return readingSeq;
    }

    public void setReadingSeq(int readingSeq) {
        this.readingSeq = readingSeq;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
