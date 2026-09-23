package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 参数解析历史（resolution 追踪记录）。
 * 每次解析命中参数版本后写入一条不可变记录，resolutionId 可追溯当时使用的版本；
 * 后续发布/回滚不修改本表，从而保证历史引用不变。
 */
public class ResolutionRecord {

    private Long id;
    /** 解析追踪 ID（UUID），对外可查询 */
    private String resolutionId;
    private String paramKey;
    private String vehicleId;
    private String sensorId;
    private String mapVersion;
    private Instant resolveTime;
    /** 当时命中的参数版本 ID */
    private Long resolvedParamId;
    private String resolvedVersion;
    private String contentDigest;
    /** 当时版本内容快照（不可变） */
    private String contentJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getResolutionId() { return resolutionId; }
    public void setResolutionId(String resolutionId) { this.resolutionId = resolutionId; }
    public String getParamKey() { return paramKey; }
    public void setParamKey(String paramKey) { this.paramKey = paramKey; }
    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }
    public String getMapVersion() { return mapVersion; }
    public void setMapVersion(String mapVersion) { this.mapVersion = mapVersion; }
    public Instant getResolveTime() { return resolveTime; }
    public void setResolveTime(Instant resolveTime) { this.resolveTime = resolveTime; }
    public Long getResolvedParamId() { return resolvedParamId; }
    public void setResolvedParamId(Long resolvedParamId) { this.resolvedParamId = resolvedParamId; }
    public String getResolvedVersion() { return resolvedVersion; }
    public void setResolvedVersion(String resolvedVersion) { this.resolvedVersion = resolvedVersion; }
    public String getContentDigest() { return contentDigest; }
    public void setContentDigest(String contentDigest) { this.contentDigest = contentDigest; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
}
