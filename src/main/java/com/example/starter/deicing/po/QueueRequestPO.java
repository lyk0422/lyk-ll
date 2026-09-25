package com.example.starter.deicing.po;

import com.example.starter.deicing.model.DeiceType;
import com.example.starter.deicing.model.QueueStatus;

/**
 * 排队申请记录（queue_request 表一行）。
 * 时刻单位：UTC epoch 毫秒；null 含义见各字段注释。
 */
public class QueueRequestPO {

    /** 申请幂等键。 */
    private String requestId;
    /** 申请序号（同刻到达定序依据）。 */
    private long seq;
    /** 航班号。 */
    private String flightNo;
    /** 机位号。 */
    private String stand;
    /** 除冰类型。 */
    private DeiceType deiceType;
    /** 所需车位数。 */
    private int requiredBays;
    /** 目标起飞时刻。 */
    private long targetDepartureTs;
    /** 最新预计起飞时刻。 */
    private long expectedDepartureTs;
    /** 截止时刻=起飞时刻减除冰时长。 */
    private long deadlineTs;
    /** 当前状态。 */
    private QueueStatus status;
    /** 作业实际开始时刻；未开始为 null。 */
    private Long actualStartTs;
    /** 作业实际完成时刻；未完成为 null。 */
    private Long actualFinishTs;
    /** 两步法防冰液失效终点；一步法或未完成为 null。 */
    private Long hotExpiresTs;
    /** HOT 风险后最早可重排时刻；无风险为 null。 */
    private Long earliestReschedTs;
    /** 取消原因；未取消为 null。 */
    private String cancelReason;
    /** 除冰轮次：首次 1，重新除冰递增。 */
    private int roundNo = 1;
    /** 创建时刻。 */
    private long createdTs;
    /** 最近更新时刻。 */
    private long updatedTs;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public void setFlightNo(String flightNo) {
        this.flightNo = flightNo;
    }

    public String getStand() {
        return stand;
    }

    public void setStand(String stand) {
        this.stand = stand;
    }

    public DeiceType getDeiceType() {
        return deiceType;
    }

    public void setDeiceType(DeiceType deiceType) {
        this.deiceType = deiceType;
    }

    public int getRequiredBays() {
        return requiredBays;
    }

    public void setRequiredBays(int requiredBays) {
        this.requiredBays = requiredBays;
    }

    public long getTargetDepartureTs() {
        return targetDepartureTs;
    }

    public void setTargetDepartureTs(long targetDepartureTs) {
        this.targetDepartureTs = targetDepartureTs;
    }

    public long getExpectedDepartureTs() {
        return expectedDepartureTs;
    }

    public void setExpectedDepartureTs(long expectedDepartureTs) {
        this.expectedDepartureTs = expectedDepartureTs;
    }

    public long getDeadlineTs() {
        return deadlineTs;
    }

    public void setDeadlineTs(long deadlineTs) {
        this.deadlineTs = deadlineTs;
    }

    public QueueStatus getStatus() {
        return status;
    }

    public void setStatus(QueueStatus status) {
        this.status = status;
    }

    public Long getActualStartTs() {
        return actualStartTs;
    }

    public void setActualStartTs(Long actualStartTs) {
        this.actualStartTs = actualStartTs;
    }

    public Long getActualFinishTs() {
        return actualFinishTs;
    }

    public void setActualFinishTs(Long actualFinishTs) {
        this.actualFinishTs = actualFinishTs;
    }

    public Long getHotExpiresTs() {
        return hotExpiresTs;
    }

    public void setHotExpiresTs(Long hotExpiresTs) {
        this.hotExpiresTs = hotExpiresTs;
    }

    public Long getEarliestReschedTs() {
        return earliestReschedTs;
    }

    public void setEarliestReschedTs(Long earliestReschedTs) {
        this.earliestReschedTs = earliestReschedTs;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public int getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(int roundNo) {
        this.roundNo = roundNo;
    }

    public long getCreatedTs() {
        return createdTs;
    }

    public void setCreatedTs(long createdTs) {
        this.createdTs = createdTs;
    }

    public long getUpdatedTs() {
        return updatedTs;
    }

    public void setUpdatedTs(long updatedTs) {
        this.updatedTs = updatedTs;
    }
}
