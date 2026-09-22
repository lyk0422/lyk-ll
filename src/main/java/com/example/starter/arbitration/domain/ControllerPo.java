package com.example.starter.arbitration.domain;

/**
 * 控制器行（t_controller）：登记心跳与失联状态。
 */
public class ControllerPo {

    /** 控制器标识，主键。 */
    private String controllerId;
    /** 最近一次心跳时间（UTC epoch 毫秒）。 */
    private long lastHeartbeatMs;
    /** 是否已被判定失联并已对其持有的资源执行过安全降级（重复检测去重标记）。 */
    private boolean lost;
    /** 行版本号（乐观锁）。 */
    private long version;

    public String getControllerId() {
        return controllerId;
    }

    public void setControllerId(String controllerId) {
        this.controllerId = controllerId;
    }

    public long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public void setLastHeartbeatMs(long lastHeartbeatMs) {
        this.lastHeartbeatMs = lastHeartbeatMs;
    }

    public boolean isLost() {
        return lost;
    }

    public void setLost(boolean lost) {
        this.lost = lost;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
