package com.example.starter.domain;

/**
 * 日前计划单段单机核定计划明细（不可变版本的组成部分）。
 * segmentIndex 时段序号 0..95（UTC 半开区间）；powerKw 非负整数，且不超过该机组合成装机容量。
 */
public class PlanSegment {

    private Long id;
    private Long versionId;
    private int segmentIndex;
    private Long turbineId;
    private int powerKw;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersionId() {
        return versionId;
    }

    public void setVersionId(Long versionId) {
        this.versionId = versionId;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public Long getTurbineId() {
        return turbineId;
    }

    public void setTurbineId(Long turbineId) {
        this.turbineId = turbineId;
    }

    public int getPowerKw() {
        return powerKw;
    }

    public void setPowerKw(int powerKw) {
        this.powerKw = powerKw;
    }
}
