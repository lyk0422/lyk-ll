package com.example.starter.ventilation.domain;

import com.example.starter.ventilation.CommandStatus;
import com.example.starter.ventilation.DeviceAction;
import com.example.starter.ventilation.DeviceType;

/**
 * 设备指令行，对应 device_command 表。
 */
public class DeviceCommandRow {

    private long id;
    private long sessionId;
    private String requestId;
    private String zoneCode;
    private String deviceCode;
    private DeviceType deviceType;
    private DeviceAction action;
    private String phase;
    private int seqNo;
    private int attempt;
    private CommandStatus status;
    private Long sentAt;
    private Long ackDueAt;
    private Long ackedAt;
    private Long faultAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getSessionId() {
        return sessionId;
    }

    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public DeviceType getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(DeviceType deviceType) {
        this.deviceType = deviceType;
    }

    public DeviceAction getAction() {
        return action;
    }

    public void setAction(DeviceAction action) {
        this.action = action;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public int getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(int seqNo) {
        this.seqNo = seqNo;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public CommandStatus getStatus() {
        return status;
    }

    public void setStatus(CommandStatus status) {
        this.status = status;
    }

    public Long getSentAt() {
        return sentAt;
    }

    public void setSentAt(Long sentAt) {
        this.sentAt = sentAt;
    }

    public Long getAckDueAt() {
        return ackDueAt;
    }

    public void setAckDueAt(Long ackDueAt) {
        this.ackDueAt = ackDueAt;
    }

    public Long getAckedAt() {
        return ackedAt;
    }

    public void setAckedAt(Long ackedAt) {
        this.ackedAt = ackedAt;
    }

    public Long getFaultAt() {
        return faultAt;
    }

    public void setFaultAt(Long faultAt) {
        this.faultAt = faultAt;
    }
}
