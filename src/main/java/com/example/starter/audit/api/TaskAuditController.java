package com.example.starter.audit.api;

import com.example.starter.audit.TaskAuditService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动驾驶任务执行审计 REST 入口。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskAuditController {

    private final TaskAuditService taskAuditService;

    public TaskAuditController(TaskAuditService taskAuditService) {
        this.taskAuditService = taskAuditService;
    }

    /** 创建任务并同步执行阶段；requestId 非空时同键同参重放、同键异参 409。 */
    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        TaskResponse response = taskAuditService.createOrReplay(request);
        // 幂等重放原结果返回 200，首次创建执行返回 201
        HttpStatus status = Boolean.TRUE.equals(response.getReplayed()) ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    /** 查询任务详情（含阶段记录）。 */
    @GetMapping("/{taskId}")
    public TaskResponse get(@PathVariable Long taskId) {
        return taskAuditService.getTask(taskId);
    }

    /**
     * 从最近成功检查点恢复失败/中断任务；恢复前重新核对全部绑定版本、检查点与依赖产物，
     * 不一致时返回 422 与不可恢复原因。
     */
    @PostMapping("/{taskId}/recover")
    public ResponseEntity<RecoverResponse> recover(@PathVariable Long taskId) {
        RecoverResponse response = taskAuditService.recover(taskId);
        if ("UNRECOVERABLE".equals(response.getStatus())) {
            return ResponseEntity.unprocessableEntity().body(response);
        }
        return ResponseEntity.ok(response);
    }

    /** 比较两次任务：绑定差异、同名指标数值差、不可比较项。 */
    @GetMapping("/compare")
    public CompareResponse compare(@RequestParam Long leftId, @RequestParam Long rightId) {
        return taskAuditService.compare(leftId, rightId);
    }
}
