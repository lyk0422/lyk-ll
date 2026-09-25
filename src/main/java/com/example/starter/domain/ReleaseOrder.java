package com.example.starter.domain;

import java.time.Instant;

/**
 * 放行单，以 requestId 幂等；同键同参返回首次结果，同键异参冲突拒绝。
 */
public class ReleaseOrder {

    private Long id;
    private String requestId;
    private String barcode;
    /** 放行时间窗起点(UTC，含)。 */
    private Instant windowStart;
    /** 放行时间窗终点(UTC，含)。 */
    private Instant windowEnd;
    /** 幂等指纹。 */
    private String fingerprint;
    private ReleaseStatus status;
    private RejectReason rejectReason;
    /** 实际发血时刻(UTC)，仅成功放行非空。 */
    private Instant issuedAt;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public ReleaseStatus getStatus() {
        return status;
    }

    public void setStatus(ReleaseStatus status) {
        this.status = status;
    }

    public RejectReason getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(RejectReason rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
