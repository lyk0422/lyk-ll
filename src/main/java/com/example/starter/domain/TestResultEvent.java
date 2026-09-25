package com.example.starter.domain;

import java.time.Instant;

/**
 * 检测结果回传事件。终态后的回传 {@code accepted=false}，只记录不复活单位。
 */
public class TestResultEvent {

    private Long id;
    private String barcode;
    private TestType testType;
    private TestResultCode result;
    /** 是否被采纳驱动状态流转；false 表示终态后的迟到事件。 */
    private boolean accepted;
    private Instant receivedAt;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public TestType getTestType() {
        return testType;
    }

    public void setTestType(TestType testType) {
        this.testType = testType;
    }

    public TestResultCode getResult() {
        return result;
    }

    public void setResult(TestResultCode result) {
        this.result = result;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
