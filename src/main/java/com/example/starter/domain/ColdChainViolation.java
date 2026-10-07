package com.example.starter.domain;

import java.time.Instant;

/**
 * 冷链核验缺口/越界点 PO：按证据版本记录，查询原样展示（缺口不是零度）。
 */
public class ColdChainViolation {

    private Long id;
    private Long ledgerId;
    /** 所属证据版本号。 */
    private int version;
    /** 缺口代码（见 ColdChainVerifier 常量）。 */
    private String code;
    /** 相关片段顺序号，空值表示与具体片段无关。 */
    private Integer segmentSeq;
    /** 相关读数顺序号，空值表示与具体读数无关。 */
    private Integer readingSeq;
    /** 相关精确时刻（UTC），空值表示无对应时刻。 */
    private Instant atTs;
    /** 相关数值（如越界温度、实际间隔秒数），空值表示无对应数值。 */
    private String value;
    private String message;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getSegmentSeq() {
        return segmentSeq;
    }

    public void setSegmentSeq(Integer segmentSeq) {
        this.segmentSeq = segmentSeq;
    }

    public Integer getReadingSeq() {
        return readingSeq;
    }

    public void setReadingSeq(Integer readingSeq) {
        this.readingSeq = readingSeq;
    }

    public Instant getAtTs() {
        return atTs;
    }

    public void setAtTs(Instant atTs) {
        this.atTs = atTs;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
