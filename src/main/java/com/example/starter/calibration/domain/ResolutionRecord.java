package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 解析记录：每次参数解析成功后写入，承载可追溯的 {@code resolutionId}。
 *
 * <p>记录当时命中的版本 ID 与灰度批次（可能为 null）。后续发布/回滚只新增数据、
 * 不修改历史记录，因此历史 resolutionId 引用的版本永不改变。</p>
 */
public class ResolutionRecord {

    /** 主键。 */
    private long id;
    /** 可追溯解析 ID（UUID）。 */
    private String resolutionId;
    /** 解析的参数键。 */
    private String paramKey;
    /** 解析时车辆编号。 */
    private String vehicleId;
    /** 解析时传感器标识。 */
    private String sensorId;
    /** 解析时地图版本。 */
    private String mapVersion;
    /** 解析时刻（UTC）。 */
    private Instant resolvedAt;
    /** 命中的标定版本 ID。 */
    private long selectedVersionId;
    /** 命中时所属进行中灰度批次 ID；基线解析为 null。 */
    private Long canaryReleaseId;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getResolutionId() {
        return resolutionId;
    }

    public void setResolutionId(String resolutionId) {
        this.resolutionId = resolutionId;
    }

    public String getParamKey() {
        return paramKey;
    }

    public void setParamKey(String paramKey) {
        this.paramKey = paramKey;
    }

    public String getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(String vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public String getMapVersion() {
        return mapVersion;
    }

    public void setMapVersion(String mapVersion) {
        this.mapVersion = mapVersion;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public long getSelectedVersionId() {
        return selectedVersionId;
    }

    public void setSelectedVersionId(long selectedVersionId) {
        this.selectedVersionId = selectedVersionId;
    }

    public Long getCanaryReleaseId() {
        return canaryReleaseId;
    }

    public void setCanaryReleaseId(Long canaryReleaseId) {
        this.canaryReleaseId = canaryReleaseId;
    }
}
