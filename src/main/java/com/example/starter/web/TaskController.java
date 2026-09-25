package com.example.starter.web;

import com.example.starter.domain.AdjustmentTask;
import com.example.starter.domain.Assessment;
import com.example.starter.service.AdjustmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调节任务流转入口：效果评估与归档。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final AdjustmentService adjustmentService;

    public TaskController(AdjustmentService adjustmentService) {
        this.adjustmentService = adjustmentService;
    }

    /**
     * 评估请求。
     *
     * @param requestId 客户端幂等键
     */
    public record AssessmentRequest(@NotBlank String requestId) {
    }

    /**
     * 查询任务详情。
     */
    @GetMapping("/{taskId}")
    public AdjustmentTask get(@PathVariable long taskId) {
        return adjustmentService.getTask(taskId);
    }

    /**
     * 效果评估：稳定满 4 小时（恰满允许）后执行，requestId 幂等。
     */
    @PostMapping("/{taskId}/assessments")
    public Assessment assess(@PathVariable long taskId,
                             @Valid @RequestBody AssessmentRequest request) {
        return adjustmentService.assess(taskId, request.requestId());
    }

    /**
     * 查询任务的评估记录（前后温度分布对比可复算）。
     */
    @GetMapping("/{taskId}/assessment")
    public Assessment assessment(@PathVariable long taskId) {
        return adjustmentService.getAssessmentByTask(taskId);
    }

    /**
     * 归档：终态，重复归档幂等成功。
     */
    @PostMapping("/{taskId}/archive")
    public AdjustmentTask archive(@PathVariable long taskId) {
        return adjustmentService.archive(taskId);
    }
}
