package com.example.starter.deicing.po;

import com.example.starter.deicing.model.DeiceType;

/**
 * 除冰时刻表行：作业时长与两步法 HOT（分钟）。
 */
public class TimetablePO {

    /** 除冰类型。 */
    private DeiceType deiceType;
    /** 作业时长（分钟）。 */
    private int durationMin;
    /** 防冰液保持时间（分钟）；一步法为 null。 */
    private Integer hotMin;

    public DeiceType getDeiceType() {
        return deiceType;
    }

    public void setDeiceType(DeiceType deiceType) {
        this.deiceType = deiceType;
    }

    public int getDurationMin() {
        return durationMin;
    }

    public void setDurationMin(int durationMin) {
        this.durationMin = durationMin;
    }

    public Integer getHotMin() {
        return hotMin;
    }

    public void setHotMin(Integer hotMin) {
        this.hotMin = hotMin;
    }
}
