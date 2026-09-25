package com.example.starter.po;

import com.example.starter.domain.Grade;
import com.example.starter.domain.OrderStatus;
import java.time.Instant;

/**
 * 故障工单持久化对象。
 */
public class FaultOrder {

    private Long id;
    /** 工单号，业务主键 */
    private String orderNo;
    /** 故障馈线 id，null 表示馈线不明 */
    private Long feederId;
    /** 故障点（接报去重键） */
    private String faultPoint;
    private String description;
    /** 影响用户数，单位：户 */
    private int affectedUsers;
    /** 接报时间（Asia/Shanghai） */
    private Instant reportTime;
    /** 停电起始时间，发电车 5 分钟计时基准 */
    private Instant outageTime;
    private OrderStatus status;
    /** 研判命中的最高保电等级快照，null=未研判 */
    private Grade userGrade;
    /** 研判命中的最高等级重要用户 id */
    private Long importantUserId;
    /** 执行班组 id，null=未派单 */
    private Long crewId;
    /** 挂单等待的目标班组 id，null=未挂单 */
    private Long waitCrewId;
    private Instant assignedTime;
    private Instant repairStartTime;
    /** 复电时间（双确认齐备时刻） */
    private Instant restoredTime;
    private Instant sealedTime;
    /** 合并后指向的新工单 id */
    private Long mergedIntoId;
    private String mergedIntoNo;
    private Instant createTime;
    private Instant updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getFeederId() {
        return feederId;
    }

    public void setFeederId(Long feederId) {
        this.feederId = feederId;
    }

    public String getFaultPoint() {
        return faultPoint;
    }

    public void setFaultPoint(String faultPoint) {
        this.faultPoint = faultPoint;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getAffectedUsers() {
        return affectedUsers;
    }

    public void setAffectedUsers(int affectedUsers) {
        this.affectedUsers = affectedUsers;
    }

    public Instant getReportTime() {
        return reportTime;
    }

    public void setReportTime(Instant reportTime) {
        this.reportTime = reportTime;
    }

    public Instant getOutageTime() {
        return outageTime;
    }

    public void setOutageTime(Instant outageTime) {
        this.outageTime = outageTime;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Grade getUserGrade() {
        return userGrade;
    }

    public void setUserGrade(Grade userGrade) {
        this.userGrade = userGrade;
    }

    public Long getImportantUserId() {
        return importantUserId;
    }

    public void setImportantUserId(Long importantUserId) {
        this.importantUserId = importantUserId;
    }

    public Long getCrewId() {
        return crewId;
    }

    public void setCrewId(Long crewId) {
        this.crewId = crewId;
    }

    public Long getWaitCrewId() {
        return waitCrewId;
    }

    public void setWaitCrewId(Long waitCrewId) {
        this.waitCrewId = waitCrewId;
    }

    public Instant getAssignedTime() {
        return assignedTime;
    }

    public void setAssignedTime(Instant assignedTime) {
        this.assignedTime = assignedTime;
    }

    public Instant getRepairStartTime() {
        return repairStartTime;
    }

    public void setRepairStartTime(Instant repairStartTime) {
        this.repairStartTime = repairStartTime;
    }

    public Instant getRestoredTime() {
        return restoredTime;
    }

    public void setRestoredTime(Instant restoredTime) {
        this.restoredTime = restoredTime;
    }

    public Instant getSealedTime() {
        return sealedTime;
    }

    public void setSealedTime(Instant sealedTime) {
        this.sealedTime = sealedTime;
    }

    public Long getMergedIntoId() {
        return mergedIntoId;
    }

    public void setMergedIntoId(Long mergedIntoId) {
        this.mergedIntoId = mergedIntoId;
    }

    public String getMergedIntoNo() {
        return mergedIntoNo;
    }

    public void setMergedIntoNo(String mergedIntoNo) {
        this.mergedIntoNo = mergedIntoNo;
    }

    public Instant getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Instant createTime) {
        this.createTime = createTime;
    }

    public Instant getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Instant updateTime) {
        this.updateTime = updateTime;
    }
}
