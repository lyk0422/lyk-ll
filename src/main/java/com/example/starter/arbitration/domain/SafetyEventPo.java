package com.example.starter.arbitration.domain;

/**
 * 安全降级事件行（t_safety_event）。每个触发键至多一条事件，用于保证重复检测不重复执行降级动作。
 */
public class SafetyEventPo {

    /** 自增主键。 */
    private Long id;
    /**
     * 幂等触发键：LEASE_EXPIRED/CONTROLLER_LOST/COMMAND_TIMEOUT 与资源及来源标识的组合，
     * 同一触发键重复检测只会产生一条事件、执行一次降级。
     */
    private String eventKey;
    /** 触发原因：LEASE_EXPIRED/CONTROLLER_LOST/COMMAND_TIMEOUT。 */
    private String reason;
    /** 涉及资源名。 */
    private String resourceName;
    /** 关联控制器（指令超时可能为空值，含义为系统侧触发）。 */
    private String controllerId;
    /** 实际执行的安全动作：HOLD/ZERO_VELOCITY/PRESET_POSITION。 */
    private String action;
    /** PRESET_POSITION 时的目标位置（弧度），其余动作为空。 */
    private Double targetPosition;
    /** 事件时间（UTC epoch 毫秒）。 */
    private long eventMs;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getControllerId() {
        return controllerId;
    }

    public void setControllerId(String controllerId) {
        this.controllerId = controllerId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Double getTargetPosition() {
        return targetPosition;
    }

    public void setTargetPosition(Double targetPosition) {
        this.targetPosition = targetPosition;
    }

    public long getEventMs() {
        return eventMs;
    }

    public void setEventMs(long eventMs) {
        this.eventMs = eventMs;
    }
}
