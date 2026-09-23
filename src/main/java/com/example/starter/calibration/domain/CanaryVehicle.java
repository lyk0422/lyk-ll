package com.example.starter.calibration.domain;

/**
 * 灰度车辆条目：记录每辆灰度车辆发布前解析的基线版本，用于回滚恢复。
 */
public class CanaryVehicle {

    /** 主键。 */
    private long id;
    /** 所属灰度批次 ID。 */
    private long releaseId;
    /** 车辆编号。 */
    private String vehicleId;
    /** 发布前该车辆解析的基线版本 ID；无基线时为 null（回滚即回到“无版本”）。 */
    private Long previousVersionId;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(long releaseId) {
        this.releaseId = releaseId;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Long getPreviousVersionId() {
        return previousVersionId;
    }

    public void setPreviousVersionId(Long previousVersionId) {
        this.previousVersionId = previousVersionId;
    }
}
