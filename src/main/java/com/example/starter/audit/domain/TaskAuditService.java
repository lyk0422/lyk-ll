package com.example.starter.audit.domain;

import com.example.starter.audit.api.CompareResult;
import com.example.starter.audit.api.CreateResult;
import com.example.starter.audit.api.CreateTaskRequest;
import com.example.starter.audit.api.RecoveryResult;
import com.example.starter.audit.api.TaskView;

/**
 * 任务执行审计服务：创建并确定性执行任务、检查点恢复、任务比较与确定性重放。
 */
public interface TaskAuditService {

    CreateResult createAndRun(CreateTaskRequest request);

    TaskView getTask(long taskId);

    RecoveryResult recover(long taskId);

    CompareResult compare(long leftTaskId, long rightTaskId);
}
