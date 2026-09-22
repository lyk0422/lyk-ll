package com.example.starter.arbitration.domain;

/**
 * 关节/执行器资源行（t_resource）。
 */
public class ResourcePo {

    /** 资源名（关节或执行器标识），主键。 */
    private String resourceName;
    /** 位置下限（弧度）。 */
    private double minPosition;
    /** 位置上限（弧度）。 */
    private double maxPosition;
    /** 最大允许速度（弧度/秒），不可为空，取值不小于 0。 */
    private double maxVelocity;
    /** 最大允许加速度（弧度/秒²），不可为空，取值不小于 0。 */
    private double maxAcceleration;
    /** 失联/失权时进入的安全动作（HOLD/ZERO_VELOCITY/PRESET_POSITION）。 */
    private String safetyAction;
    /** PRESET_POSITION 使用的预设位置（弧度），其余动作下为空值含义。 */
    private Double presetPosition;
    /** 最近一次应用到该资源的目标位置（弧度），用于限速校验与 HOLD；无指令时为空。 */
    private Double lastPosition;
    /** 最近一次应用目标的时戳（UTC epoch 毫秒），无指令时为空。 */
    private Long lastTimestampMs;

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public double getMinPosition() {
        return minPosition;
    }

    public void setMinPosition(double minPosition) {
        this.minPosition = minPosition;
    }

    public double getMaxPosition() {
        return maxPosition;
    }

    public void setMaxPosition(double maxPosition) {
        this.maxPosition = maxPosition;
    }

    public double getMaxVelocity() {
        return maxVelocity;
    }

    public void setMaxVelocity(double maxVelocity) {
        this.maxVelocity = maxVelocity;
    }

    public double getMaxAcceleration() {
        return maxAcceleration;
    }

    public void setMaxAcceleration(double maxAcceleration) {
        this.maxAcceleration = maxAcceleration;
    }

    public String getSafetyAction() {
        return safetyAction;
    }

    public void setSafetyAction(String safetyAction) {
        this.safetyAction = safetyAction;
    }

    public Double getPresetPosition() {
        return presetPosition;
    }

    public void setPresetPosition(Double presetPosition) {
        this.presetPosition = presetPosition;
    }

    public Double getLastPosition() {
        return lastPosition;
    }

    public void setLastPosition(Double lastPosition) {
        this.lastPosition = lastPosition;
    }

    public Long getLastTimestampMs() {
        return lastTimestampMs;
    }

    public void setLastTimestampMs(Long lastTimestampMs) {
        this.lastTimestampMs = lastTimestampMs;
    }
}
