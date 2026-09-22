package com.example.starter.orchestrator;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.orchestrator.dto.EventView;
import com.example.starter.orchestrator.dto.PlanSubmitRequest;
import com.example.starter.orchestrator.dto.PlanView;

import jakarta.validation.Valid;

/**
 * 编排计划 REST API。
 */
@RestController
@RequestMapping("/api/plans")
@Validated
public class PlanController {

    private final OrchestratorService service;

    public PlanController(OrchestratorService service) {
        this.service = service;
    }

    /**
     * 提交计划；同一 requestId 同参幂等返回 200，首次创建返回 201。
     */
    @PostMapping
    public ResponseEntity<PlanView> submit(@Valid @RequestBody PlanSubmitRequest request) {
        PlanView view = service.submit(request);
        return ResponseEntity.status(view.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    /**
     * 执行计划。
     */
    @PostMapping("/{planId}/execute")
    public PlanView execute(@PathVariable String planId) {
        return service.execute(planId);
    }

    /**
     * 恢复未完成计划。
     */
    @PostMapping("/{planId}/resume")
    public PlanView resume(@PathVariable String planId) {
        return service.resume(planId);
    }

    /**
     * 幂等取消计划。
     */
    @PostMapping("/{planId}/cancel")
    public PlanView cancel(@PathVariable String planId) {
        return service.cancel(planId);
    }

    /**
     * 查询计划。
     */
    @GetMapping("/{planId}")
    public PlanView get(@PathVariable String planId) {
        return service.getPlan(planId);
    }

    /**
     * 查询计划事件日志。
     */
    @GetMapping("/{planId}/events")
    public List<EventView> events(@PathVariable String planId) {
        return service.getEvents(planId);
    }
}
