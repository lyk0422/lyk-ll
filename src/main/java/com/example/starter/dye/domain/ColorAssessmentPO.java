package com.example.starter.dye.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打小样对色记录 PO。
 */
public class ColorAssessmentPO {
    private Long id;
    private String requestId;
    private String sampleCode;
    private BigDecimal stdL;
    private BigDecimal stdA;
    private BigDecimal stdB;
    private BigDecimal sampleL;
    private BigDecimal sampleA;
    private BigDecimal sampleB;
    private BigDecimal deltaE;
    private boolean passed;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSampleCode() { return sampleCode; }
    public void setSampleCode(String sampleCode) { this.sampleCode = sampleCode; }
    public BigDecimal getStdL() { return stdL; }
    public void setStdL(BigDecimal stdL) { this.stdL = stdL; }
    public BigDecimal getStdA() { return stdA; }
    public void setStdA(BigDecimal stdA) { this.stdA = stdA; }
    public BigDecimal getStdB() { return stdB; }
    public void setStdB(BigDecimal stdB) { this.stdB = stdB; }
    public BigDecimal getSampleL() { return sampleL; }
    public void setSampleL(BigDecimal sampleL) { this.sampleL = sampleL; }
    public BigDecimal getSampleA() { return sampleA; }
    public void setSampleA(BigDecimal sampleA) { this.sampleA = sampleA; }
    public BigDecimal getSampleB() { return sampleB; }
    public void setSampleB(BigDecimal sampleB) { this.sampleB = sampleB; }
    public BigDecimal getDeltaE() { return deltaE; }
    public void setDeltaE(BigDecimal deltaE) { this.deltaE = deltaE; }
    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
