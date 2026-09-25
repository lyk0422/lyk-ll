package com.example.starter.marpol.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 港口国核查任务（表 inspection），按船舶发起。
 */
public class Inspection {

    private String inspectionNo;
    private String shipId;
    private String portCode;
    /** 核查日（UTC 日期）。 */
    private LocalDate inspectDate;
    /** OPEN / ISSUED。 */
    private InspectionStatus status;
    /** 复查时引用的原核查号；普通核查为 null。 */
    private String reviewOf;
    /** 签发时刻（UTC），终态判定依据。 */
    private Instant issuedAt;
    private Instant createdAt;

    public String getInspectionNo() {
        return inspectionNo;
    }

    public void setInspectionNo(String inspectionNo) {
        this.inspectionNo = inspectionNo;
    }

    public String getShipId() {
        return shipId;
    }

    public void setShipId(String shipId) {
        this.shipId = shipId;
    }

    public String getPortCode() {
        return portCode;
    }

    public void setPortCode(String portCode) {
        this.portCode = portCode;
    }

    public LocalDate getInspectDate() {
        return inspectDate;
    }

    public void setInspectDate(LocalDate inspectDate) {
        this.inspectDate = inspectDate;
    }

    public InspectionStatus getStatus() {
        return status;
    }

    public void setStatus(InspectionStatus status) {
        this.status = status;
    }

    public String getReviewOf() {
        return reviewOf;
    }

    public void setReviewOf(String reviewOf) {
        this.reviewOf = reviewOf;
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
