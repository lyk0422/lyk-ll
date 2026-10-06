package com.example.starter.transition.model;

import java.time.LocalDateTime;

/**
 * 掺配执行控制状态：失控即暂停执行转人工，复核确认后恢复。
 */
public class BlendControlState {

    /** 控制状态：执行中。 */
    public static final String RUNNING = "RUNNING";
    /** 控制状态：失控暂停转人工。 */
    public static final String PAUSED_MANUAL = "PAUSED_MANUAL";

    private String orderNo;
    /** 控制状态：RUNNING=执行中，PAUSED_MANUAL=失控暂停转人工。 */
    private String controlStatus;
    /** 当前生效比例版本号。 */
    private Integer currentVersionNo;
    /** 当前版本内连续超差采样点数，达 3 判失控；恢复或换版后清零。 */
    private Integer outStreak;
    private LocalDateTime updatedAt;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getControlStatus() {
        return controlStatus;
    }

    public void setControlStatus(String controlStatus) {
        this.controlStatus = controlStatus;
    }

    public Integer getCurrentVersionNo() {
        return currentVersionNo;
    }

    public void setCurrentVersionNo(Integer currentVersionNo) {
        this.currentVersionNo = currentVersionNo;
    }

    public Integer getOutStreak() {
        return outStreak;
    }

    public void setOutStreak(Integer outStreak) {
        this.outStreak = outStreak;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
