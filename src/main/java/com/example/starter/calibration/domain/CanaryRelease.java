package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 灰度发布单：一次灰度发布显式指定车辆集合，对应一个新版本参数。
 */
public class CanaryRelease {

    private Long id;
    /** 发布单号，例如 REL-20260923-000001 */
    private String releaseId;
    private Long paramId;
    private String paramKey;
    private String newVersion;
    /** 参与灰度的车辆集合 JSON */
    private String vehiclesJson;
    private ReleaseStatus status;
    private String createdBy;
    private Instant createdAt;
    private String finishedBy;
    private Instant finishedAt;
    private String remark;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReleaseId() { return releaseId; }
    public void setReleaseId(String releaseId) { this.releaseId = releaseId; }
    public Long getParamId() { return paramId; }
    public void setParamId(Long paramId) { this.paramId = paramId; }
    public String getParamKey() { return paramKey; }
    public void setParamKey(String paramKey) { this.paramKey = paramKey; }
    public String getNewVersion() { return newVersion; }
    public void setNewVersion(String newVersion) { this.newVersion = newVersion; }
    public String getVehiclesJson() { return vehiclesJson; }
    public void setVehiclesJson(String vehiclesJson) { this.vehiclesJson = vehiclesJson; }
    public ReleaseStatus getStatus() { return status; }
    public void setStatus(ReleaseStatus status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getFinishedBy() { return finishedBy; }
    public void setFinishedBy(String finishedBy) { this.finishedBy = finishedBy; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
