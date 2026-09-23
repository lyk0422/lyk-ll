package com.example.starter.calibration.domain;

/**
 * 灰度车辆明细行对象，对应 canary_vehicle 表。
 */
public class CanaryVehicle {

    private Long id;
    private Long releaseId;
    private String vehicleId;
    private Long previousVersionId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReleaseId() {
        return releaseId;
    }

    public void setReleaseId(Long releaseId) {
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
