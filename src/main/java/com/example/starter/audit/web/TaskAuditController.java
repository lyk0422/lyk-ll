package com.example.starter.audit.web;

import com.example.starter.audit.api.CompareResult;
import com.example.starter.audit.api.CreateResult;
import com.example.starter.audit.api.CreateTaskRequest;
import com.example.starter.audit.api.RecoveryResult;
import com.example.starter.audit.api.TaskView;
import com.example.starter.audit.domain.TaskAuditService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动驾驶任务执行审计 HTTP 入口。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskAuditController {

    private final TaskAuditService service;

    public TaskAuditController(TaskAuditService service) {
        this.service = service;
    }

    /** 创建任务并按阶段顺序同步执行；版本缺失返回 422，同键同参重放返回 200。 */
    @PostMapping
    public ResponseEntity<TaskView> create(@Valid @RequestBody CreateTaskRequest request) {
        CreateResult result = service.createAndRun(request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.task());
    }

    /** 查询任务及全部阶段记录。 */
    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable long taskId) {
        return service.getTask(taskId);
    }

    /** 从最近成功检查点恢复失败或中断的任务。 */
    @PostMapping("/{taskId}/recover")
    public RecoveryResult recover(@PathVariable long taskId) {
        return service.recover(taskId);
    }

    /** 比较两次任务的绑定版本、种子与同名指标。 */
    @GetMapping("/{leftTaskId}/compare/{rightTaskId}")
    public CompareResult compare(@PathVariable long leftTaskId, @PathVariable long rightTaskId) {
        return service.compare(leftTaskId, rightTaskId);
    }
}
