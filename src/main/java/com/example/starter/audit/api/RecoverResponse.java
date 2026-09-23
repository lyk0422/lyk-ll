package com.example.starter.audit.api;

/**
 * 恢复失败响应体：携带不可恢复原因。
 */
public class RecoverResponse {

    private Long taskId;
    private String status;
    private String reason;
    private TaskResponse task;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public TaskResponse getTask() {
        return task;
    }

    public void setTask(TaskResponse task) {
        this.task = task;
    }
}
