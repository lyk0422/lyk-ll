package com.example.starter.arbitration.domain;

/**
 * 当前有效资源占用行（t_active_claim）。resource_name 主键保证任一资源同一时刻只有一个持有者。
 */
public class ActiveClaimPo {

    /** 被占有的资源名，主键。 */
    private String resourceName;
    /** 对应 t_lease 主键。 */
    private long leaseId;
    /** 持有方控制器标识。 */
    private String controllerId;
    /** 申请幂等键。 */
    private String requestId;
    /** 优先级，数值越大优先级越高。 */
    private int priority;
    /** 授权时间（UTC epoch 毫秒）。 */
    private long acquiredAtMs;
    /** 租约到期时间（UTC epoch 毫秒）。 */
    private long expiresAtMs;

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public long getLeaseId() {
        return leaseId;
    }

    public void setLeaseId(long leaseId) {
        this.leaseId = leaseId;
    }

    public String getControllerId() {
        return controllerId;
    }

    public void setControllerId(String controllerId) {
        this.controllerId = controllerId;
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
}
