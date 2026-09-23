package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 标定参数版本（持久化实体）。
 *
 * <p>每份参数包含：唯一键 {@code paramKey}、传感器类型、参数类别、版本号、内容摘要（SHA-256）、
 * 审批状态、生效时间范围（{@code effectiveFrom}/{@code effectiveTo}，UTC，null 端表示开放），
 * 以及车辆/传感器/地图版本适用范围（闭区间字符串，null 端表示不限）。</p>
 */
public class CalibrationVersion {

    /** 主键，由数据库生成。 */
    private long id;
    /** 参数唯一键，例如 cam_front/intrinsic；同一键下可有多个版本。 */
    private String paramKey;
    /** 传感器类型：CAMERA/LIDAR/RADAR/IMU。 */
    private SensorType sensorType;
    /** 参数类别：INTRINSIC/EXTRINSIC/IMU。 */
    private ParamKind kind;
    /** 版本号（同一 paramKey 下唯一），例如 1.0.0。 */
    private String version;
    /** 参数内容 SHA-256 摘要（十六进制）。 */
    private String contentSha256;
    /** 参数内容 JSON 原文。 */
    private String contentJson;
    /** 生命周期状态。 */
    private CalibrationStatus status;

    /** 生效起始时间（UTC，含）；null 表示立即生效。 */
    private Instant effectiveFrom;
    /** 生效结束时间（UTC，不含）；null 表示长期有效。 */
    private Instant effectiveTo;

    /** 适用车辆编号范围下限（含，字典序）；null 表示不限。 */
    private String vehicleFrom;
    /** 适用车辆编号范围上限（含，字典序）；null 表示不限。 */
    private String vehicleTo;
    /** 适用传感器标识/型号范围下限（含，字典序）；null 表示不限。 */
    private String sensorFrom;
    /** 适用传感器标识/型号范围上限（含，字典序）；null 表示不限。 */
    private String sensorTo;
    /** 适用地图版本范围下限（含，字典序）；null 表示不限。 */
    private String mapFrom;
    /** 适用地图版本范围上限（含，字典序）；null 表示不限。 */
    private String mapTo;

    /** 创建操作者。 */
    private String createdBy;
    /** 创建时间（UTC）。 */
    private Instant createdAt;
    /** 审批操作者；null 表示未审批。 */
    private String approvedBy;
    /** 审批时间（UTC）；null 表示未审批。 */
    private Instant approvedAt;
    /** 全量生效操作者；null 表示未全量生效。 */
    private String activatedBy;
    /** 全量生效时间（UTC）；null 表示未全量生效。 */
    private Instant activatedAt;
    /** 回滚操作者；null 表示未回滚。 */
    private String rolledBackBy;
    /** 回滚时间（UTC）；null 表示未回滚。 */
    private Instant rolledBackAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getParamKey() {
        return paramKey;
    }

    public void setParamKey(String paramKey) {
        this.paramKey = paramKey;
    }

    public SensorType getSensorType() {
        return sensorType;
    }

    public void setSensorType(SensorType sensorType) {
        this.sensorType = sensorType;
    }

    public ParamKind getKind() {
        return kind;
    }

    public void setKind(ParamKind kind) {
        this.kind = kind;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public String getContentJson() {
        return contentJson;
    }

    public void setContentJson(String contentJson) {
        this.contentJson = contentJson;
    }

    public CalibrationStatus getStatus() {
        return status;
    }

    public void setStatus(CalibrationStatus status) {
        this.status = status;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(Instant effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(Instant effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getVehicleFrom() {
        return vehicleFrom;
    }

    public void setVehicleFrom(String vehicleFrom) {
        this.vehicleFrom = vehicleFrom;
    }

    public String getVehicleTo() {
        return vehicleTo;
    }

    public void setVehicleTo(String vehicleTo) {
        this.vehicleTo = vehicleTo;
    }

    public String getSensorFrom() {
        return sensorFrom;
    }

    public void setSensorFrom(String sensorFrom) {
        this.sensorFrom = sensorFrom;
    }

    public String getSensorTo() {
        return sensorTo;
    }

    public void setSensorTo(String sensorTo) {
        this.sensorTo = sensorTo;
    }

    public String getMapFrom() {
        return mapFrom;
    }

    public void setMapFrom(String mapFrom) {
        this.mapFrom = mapFrom;
    }

    public String getMapTo() {
        return mapTo;
    }

    public void setMapTo(String mapTo) {
        this.mapTo = mapTo;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public void setActivatedBy(String activatedBy) {
        this.activatedBy = activatedBy;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public String getRolledBackBy() {
        return rolledBackBy;
    }

    public void setRolledBackBy(String rolledBackBy) {
        this.rolledBackBy = rolledBackBy;
    }

    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    public void setRolledBackAt(Instant rolledBackAt) {
        this.rolledBackAt = rolledBackAt;
    }
}
