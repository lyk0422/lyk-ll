package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 标定参数版本实体（calibration_param 表对应对象）。
 */
public class CalibrationParam {

    private Long id;
    /** 全局唯一参数键，例如 cam_front.intrinsic */
    private String paramKey;
    private CalibrationKind kind;
    private SensorType sensorType;
    /** 版本号，同一参数键下唯一 */
    private String version;
    /** 参数内容（JSON 字符串） */
    private String contentJson;
    /** 内容摘要（SHA-256，hex） */
    private String contentDigest;
    private ParamStatus status;
    /** 生效起始时间（UTC，含），null 表示不限制 */
    private Instant effectiveFrom;
    /** 生效结束时间（UTC，不含），null 表示不限制 */
    private Instant effectiveTo;
    /** 适用车辆集合，存储为 JSON 数组字符串；null/空表示全部车辆 */
    private String vehicleScopeJson;
    /** 适用传感器集合，存储为 JSON 数组字符串；null/空表示该键对应传感器 */
    private String sensorScopeJson;
    /** 适用地图版本下界（含），null 表示不限制 */
    private String mapVersionMin;
    /** 适用地图版本上界（含），null 表示不限制 */
    private String mapVersionMax;

    private String createdBy;
    private Instant createdAt;
    private String approvedBy;
    private Instant approvedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getParamKey() { return paramKey; }
    public void setParamKey(String paramKey) { this.paramKey = paramKey; }
    public CalibrationKind getKind() { return kind; }
    public void setKind(CalibrationKind kind) { this.kind = kind; }
    public SensorType getSensorType() { return sensorType; }
    public void setSensorType(SensorType sensorType) { this.sensorType = sensorType; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public String getContentDigest() { return contentDigest; }
    public void setContentDigest(String contentDigest) { this.contentDigest = contentDigest; }
    public ParamStatus getStatus() { return status; }
    public void setStatus(ParamStatus status) { this.status = status; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(Instant effectiveTo) { this.effectiveTo = effectiveTo; }
    public String getVehicleScopeJson() { return vehicleScopeJson; }
    public void setVehicleScopeJson(String vehicleScopeJson) { this.vehicleScopeJson = vehicleScopeJson; }
    public String getSensorScopeJson() { return sensorScopeJson; }
    public void setSensorScopeJson(String sensorScopeJson) { this.sensorScopeJson = sensorScopeJson; }
    public String getMapVersionMin() { return mapVersionMin; }
    public void setMapVersionMin(String mapVersionMin) { this.mapVersionMin = mapVersionMin; }
    public String getMapVersionMax() { return mapVersionMax; }
    public void setMapVersionMax(String mapVersionMax) { this.mapVersionMax = mapVersionMax; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
}
