package com.example.starter.perception.domain;

import java.time.Instant;

/**
 * 轨迹当前态实体（track 表一行）。
 *
 * <p>位置单位为米（局部平面坐标系），所有时间为 UTC。
 */
public class Track {

    /** 轨迹业务ID。 */
    private String trackId;
    /** 目标类别，仅同类目标参与关联。 */
    private String category;
    /** 状态机当前状态。 */
    private TrackState state;
    /** 最近一次有效检测的置信度，范围[0,1]。 */
    private double confidence;
    /** 最近观测位置X，单位米。 */
    private double posX;
    /** 最近观测位置Y，单位米。 */
    private double posY;
    /** 最近一次有效观测时间(UTC)。 */
    private Instant lastObservedAt;
    /** 最近一次关联证据JSON。 */
    private String evidence;
    /** 乐观锁版本号，每次有效变化严格加1。 */
    private long version;
    /** 轨迹创建时间(UTC)。 */
    private Instant createdAt;
    /** 记录最近更新时间(UTC)。 */
    private Instant updatedAt;

    public String getTrackId() {
        return trackId;
    }

    public void setTrackId(String trackId) {
        this.trackId = trackId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public TrackState getState() {
        return state;
    }

    public void setState(TrackState state) {
        this.state = state;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public double getPosX() {
        return posX;
    }

    public void setPosX(double posX) {
        this.posX = posX;
    }

    public double getPosY() {
        return posY;
    }

    public void setPosY(double posY) {
        this.posY = posY;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public void setLastObservedAt(Instant lastObservedAt) {
        this.lastObservedAt = lastObservedAt;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
