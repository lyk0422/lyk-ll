package com.example.starter.arbitration.domain;

/**
 * 资源写权限租约行（t_lease）。任一资源同一时刻至多一条 active=1 的租约。
 */
public class LeasePo {

    /** 自增主键。 */
    private Long id;
    /** 持有方控制器标识。 */
    private String controllerId;
    /** 被占有的资源名。 */
    private String resourceName;
    /** 申请幂等键：同一 controllerId+requestId 的重复申请必须幂等。 */
    private String requestId;
    /** 优先级，数值越大优先级越高；高优先级可抢占低优先级，同级不可抢占。 */
    private int priority;
    /** 授权时间（UTC epoch 毫秒）。 */
    private long acquiredAtMs;
    /** 租约到期时间（UTC epoch 毫秒）。 */
    private long expiresAtMs;
    /** 是否为当前有效持有者（1 有效，0 已释放/被抢占/被急停撤销/到期）。 */
    private boolean active;
    /** 失效原因：RELEASED/PREEMPTED/ESTOP/LEASE_EXPIRED/CONTROLLER_LOST，有效时为空。 */
    private String revokedReason;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getControllerId() {
        return controllerId;
    }

    public void setControllerId(String controllerId) {
        this.controllerId = controllerId;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public long getAcquiredAtMs() {
        return acquiredAtMs;
    }

    public void setAcquiredAtMs(long acquiredAtMs) {
        this.acquiredAtMs = acquiredAtMs;
    }

    public long getExpiresAtMs() {
        return expiresAtMs;
    }

    public void setExpiresAtMs(long expiresAtMs) {
        this.expiresAtMs = expiresAtMs;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public void setRevokedReason(String revokedReason) {
        this.revokedReason = revokedReason;
    }
}
