package com.example.starter.arbitration.domain;

/**
 * 紧急停止全局状态行（t_estop_state，单行 id=1）。
 */
public class EstopStatePo {

    /** 固定主键 1。 */
    private long id;
    /** 是否处于急停状态：1 急停生效中（拒绝普通申请），0 正常。 */
    private boolean active;
    /** 最近一次急停触发时间（UTC epoch 毫秒），从未触发为空。 */
    private Long triggeredAtMs;
    /** 最近一次显式恢复时间（UTC epoch 毫秒），从未恢复为空。 */
    private Long recoveredAtMs;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getTriggeredAtMs() {
        return triggeredAtMs;
    }

    public void setTriggeredAtMs(Long triggeredAtMs) {
        this.triggeredAtMs = triggeredAtMs;
    }

    public Long getRecoveredAtMs() {
        return recoveredAtMs;
    }

    public void setRecoveredAtMs(Long recoveredAtMs) {
        this.recoveredAtMs = recoveredAtMs;
    }
}
