package com.example.starter.orchestrator.api;

import com.example.starter.orchestrator.service.LaunchPlanService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ROS2 Launch 风格依赖编排器 HTTP 入口（模拟，无真实进程编排）。
 */
@RestController
@RequestMapping("/api/launch/plans")
public class LaunchPlanController {

    private final LaunchPlanService service;

    public LaunchPlanController(LaunchPlanService service) {
        this.service = service;
    }

    /**
     * 提交计划：新建与同 requestId 同参幂等命中均返回 200 与当前计划状态。
     * 异参重复提交返回 409，循环依赖返回 422。
     */
    @PostMapping
    public ResponseEntity<PlanResponse> submit(@Valid @RequestBody SubmitPlanRequest request) {
        return ResponseEntity.ok(service.submit(request));
    }

    @GetMapping("/{planId}")
    public PlanResponse get(@PathVariable String planId) {
        return service.getPlan(planId);
    }

    @PostMapping("/{planId}/cancel")
    public PlanResponse cancel(@PathVariable String planId,
                               @Valid @RequestBody CancelPlanRequest request) {
        return service.cancel(planId, request);
    }

    @PostMapping("/{planId}/resume")
    public PlanResponse resume(@PathVariable String planId) {
        return service.resume(planId);
    }
}
