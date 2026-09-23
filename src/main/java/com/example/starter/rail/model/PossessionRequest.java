package com.example.starter.rail.model;

import java.time.Instant;
import java.util.List;

/**
 * 封锁申请持久化对象。所有时刻均为 UTC（以 epoch 毫秒存储）。
 */
public class PossessionRequest {

    /** 申请唯一标识，由客户端生成。 */
    private String requestId;
    /** 半开时间窗开始（含），UTC epoch 毫秒。 */
    private long startTime;
    /** 半开时间窗结束（不含），UTC epoch 毫秒。 */
    private long endTime;
    /** 前安全缓冲，单位毫秒，非负。 */
    private long bufferBeforeMs;
    /** 后安全缓冲，单位毫秒，非负。 */
    private long bufferAfterMs;
    /** 优先级，数值越大越紧急。 */
    private int priority;
    /** 乐观版本号，每次合法状态迁移递增。 */
    private int version;
    /** 当前状态。 */
    private RequestStatus status;
    /** 关联区段，按提交顺序保存。 */
    private List<String> sectionIds = List.of();
    private long createdAt;
    private long updatedAt;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public long getBufferBeforeMs() {
        return bufferBeforeMs;
    }

    public void setBufferBeforeMs(long bufferBeforeMs) {
        this.bufferBeforeMs = bufferBeforeMs;
    }

    public long getBufferAfterMs() {
        return bufferAfterMs;
    }

    public void setBufferAfterMs(long bufferAfterMs) {
        this.bufferAfterMs = bufferAfterMs;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public List<String> getSectionIds() {
        return sectionIds;
    }

    public void setSectionIds(List<String> sectionIds) {
        this.sectionIds = sectionIds;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 加缓冲后的实际占用窗开始（含）。 */
    public long bufferedStart() {
        return startTime - bufferBeforeMs;
    }

    /** 加缓冲后的实际占用窗结束（不含）。 */
    public long bufferedEnd() {
        return endTime + bufferAfterMs;
    }

    /** 序列化为快照时使用的业务时刻表示。 */
    public Instant startInstant() {
        return Instant.ofEpochMilli(startTime);
    }

    public Instant endInstant() {
        return Instant.ofEpochMilli(endTime);
    }
}
