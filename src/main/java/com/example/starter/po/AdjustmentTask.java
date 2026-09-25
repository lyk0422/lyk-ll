package com.example.starter.po;

import com.example.starter.domain.TaskStatus;

import java.time.Instant;

/**
 * 调节任务 PO（表 adjustment_task）。
 *
 * <p>{@code activeStationId} 执行中等于 {@code stationId}，归档后置空；
 * 该列上的唯一约束保证同一换热站同时刻只有一个执行中任务。</p>
 */
public class AdjustmentTask {
    private Long id;
    /** 换热站ID */
    private Long stationId;
    /** 首条调节令幂等键 */
    private String requestId;
    /** 任务状态 */
    private TaskStatus status;
    /** 执行中=stationId，归档后=NULL，并发唯一约束载体 */
    private Long activeStationId;
    /** 创建时刻(UTC) */
    private Instant createdAt;
    /** 归档时刻(UTC)，NULL 表示未归档 */
    private Instant archivedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public Long getActiveStationId() { return activeStationId; }
    public void setActiveStationId(Long activeStationId) { this.activeStationId = activeStationId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
}
