package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 灰度车辆明细：记录每辆灰度车辆发布前解析到的版本，回滚时恢复到该版本。
 */
public class CanaryVehicle {

    private Long id;
    private Long releaseId;
    private String vehicleId;
    /** 发布前该车辆解析到的参数版本 ID；null 表示此前无有效版本 */
    private Long previousParamId;
    private String previousVersion;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReleaseId() { return releaseId; }
    public void setReleaseId(Long releaseId) { this.releaseId = releaseId; }
    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
    public Long getPreviousParamId() { return previousParamId; }
    public void setPreviousParamId(Long previousParamId) { this.previousParamId = previousParamId; }
    public String getPreviousVersion() { return previousVersion; }
    public void setPreviousVersion(String previousVersion) { this.previousVersion = previousVersion; }
}
