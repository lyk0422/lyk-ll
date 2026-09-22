package com.example.starter.perception.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 轨迹关联与状态机参数（application.yaml: perception.track.*）。
 */
@ConfigurationProperties(prefix = "perception.track")
public class TrackProperties {

    /** 低于该置信度的检测直接拒绝。 */
    private double confidenceThreshold = 0.5;
    /** 主动态目标关联门限（米）。 */
    private double associationGateMeter = 2.0;
    /** LOST 目标重识别门限（米）。 */
    private double reidentificationGateMeter = 5.0;
    /** TRACKING 多久无观测转 OCCLUDED（毫秒）。 */
    private long occlusionGapMs = 500;
    /** OCCLUDED 多久无观测转 LOST（毫秒）。 */
    private long lostGapMs = 1500;

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public void setConfidenceThreshold(double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
    }

    public double getAssociationGateMeter() {
        return associationGateMeter;
    }

    public void setAssociationGateMeter(double associationGateMeter) {
        this.associationGateMeter = associationGateMeter;
    }

    public double getReidentificationGateMeter() {
        return reidentificationGateMeter;
    }

    public void setReidentificationGateMeter(double reidentificationGateMeter) {
        this.reidentificationGateMeter = reidentificationGateMeter;
    }

    public long getOcclusionGapMs() {
        return occlusionGapMs;
    }

    public void setOcclusionGapMs(long occlusionGapMs) {
        this.occlusionGapMs = occlusionGapMs;
    }

    public long getLostGapMs() {
        return lostGapMs;
    }

    public void setLostGapMs(long lostGapMs) {
        this.lostGapMs = lostGapMs;
    }
}
