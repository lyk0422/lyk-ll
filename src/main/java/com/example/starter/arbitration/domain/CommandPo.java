package com.example.starter.arbitration.domain;

/**
 * 控制器指令行（t_command）。校验失败的指令不写入（非法指令不进入执行队列）。
 */
public class CommandPo {

    /** 自增主键。 */
    private Long id;
    /** 发送指令的控制器（必须持有目标资源有效租约）。 */
    private String controllerId;
    /** 目标资源名。 */
    private String resourceName;
    /** 控制器提供的请求幂等键。 */
    private String requestId;
    /** 指令时戳（UTC epoch 毫秒，必须随同资源序列号单调）。 */
    private long timestampMs;
    /** 控制器内单调递增序列号。 */
    private long sequence;
    /** 目标位置（弧度）。 */
    private double targetPosition;
    /** 目标速度（弧度/秒）。 */
    private double targetVelocity;
    /** 目标加速度（弧度/秒²）。 */
    private double targetAcceleration;
    /** 状态：PENDING/EXECUTED/REJECTED/TIMED_OUT。 */
    private String status;
    /** 入队时间（UTC epoch 毫秒）。 */
    private long enqueuedAtMs;
    /** 终态时间（UTC epoch 毫秒），PENDING 时为空。 */
    private Long finishedAtMs;
    /** REJECTED 时的拒绝原因，其余状态为空。 */
    private String rejectReason;

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

    public long getTimestampMs() {
        return timestampMs;
    }

    public void setTimestampMs(long timestampMs) {
        this.timestampMs = timestampMs;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public double getTargetPosition() {
        return targetPosition;
    }

    public void setTargetPosition(double targetPosition) {
        this.targetPosition = targetPosition;
    }

    public double getTargetVelocity() {
        return targetVelocity;
    }

    public void setTargetVelocity(double targetVelocity) {
        this.targetVelocity = targetVelocity;
    }

    public double getTargetAcceleration() {
        return targetAcceleration;
    }

    public void setTargetAcceleration(double targetAcceleration) {
        this.targetAcceleration = targetAcceleration;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getEnqueuedAtMs() {
        return enqueuedAtMs;
    }

    public void setEnqueuedAtMs(long enqueuedAtMs) {
        this.enqueuedAtMs = enqueuedAtMs;
    }

    public Long getFinishedAtMs() {
        return finishedAtMs;
    }

    public void setFinishedAtMs(Long finishedAtMs) {
        this.finishedAtMs = finishedAtMs;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }
}
