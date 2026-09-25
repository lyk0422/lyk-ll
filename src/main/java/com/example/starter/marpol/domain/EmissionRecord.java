package com.example.starter.marpol.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 排放记录的一个不可变版本行（表 emission_record）。
 * 修正不覆盖旧行，而是新增版本并把旧版本置为 SUPERSEDED。
 */
public class EmissionRecord {

    private Long id;
    /** 逻辑记录号，同一条排放的所有版本共用。 */
    private String recordNo;
    /** 版本号，从 1 起。 */
    private Integer version;
    /** 幂等键；修正请求使用各自的 requestId。 */
    private String requestId;
    private String shipId;
    private String voyageNo;
    private DischargeType dischargeType;
    /** 纬度（十进制度，WGS84）。 */
    private BigDecimal latitude;
    /** 经度（十进制度，WGS84）。 */
    private BigDecimal longitude;
    /** 排放时刻（UTC）。 */
    private Instant eventTime;
    /** 数量（>0）。 */
    private BigDecimal quantity;
    /** 数量单位，如 m³ / kg。 */
    private String unit;
    /** 提交时判定的合规结论。 */
    private boolean compliant;
    /** 合规判定说明（距最近陆地海里数、命中区域等）。 */
    private String complianceDetail;
    private RecordStatus status;
    /** 修正时引用的原记录号。 */
    private String supersedesRecordNo;
    /** 修正时引用的原版本号。 */
    private Integer supersedesVersion;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRecordNo() {
        return recordNo;
    }

    public void setRecordNo(String recordNo) {
        this.recordNo = recordNo;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getShipId() {
        return shipId;
    }

    public void setShipId(String shipId) {
        this.shipId = shipId;
    }

    public String getVoyageNo() {
        return voyageNo;
    }

    public void setVoyageNo(String voyageNo) {
        this.voyageNo = voyageNo;
    }

    public DischargeType getDischargeType() {
        return dischargeType;
    }

    public void setDischargeType(DischargeType dischargeType) {
        this.dischargeType = dischargeType;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public boolean isCompliant() {
        return compliant;
    }

    public void setCompliant(boolean compliant) {
        this.compliant = compliant;
    }

    public String getComplianceDetail() {
        return complianceDetail;
    }

    public void setComplianceDetail(String complianceDetail) {
        this.complianceDetail = complianceDetail;
    }

    public RecordStatus getStatus() {
        return status;
    }

    public void setStatus(RecordStatus status) {
        this.status = status;
    }

    public String getSupersedesRecordNo() {
        return supersedesRecordNo;
    }

    public void setSupersedesRecordNo(String supersedesRecordNo) {
        this.supersedesRecordNo = supersedesRecordNo;
    }

    public Integer getSupersedesVersion() {
        return supersedesVersion;
    }

    public void setSupersedesVersion(Integer supersedesVersion) {
        this.supersedesVersion = supersedesVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
