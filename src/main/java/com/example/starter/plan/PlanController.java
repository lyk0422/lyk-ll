package com.example.starter.plan;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.plan.dto.ApproveRequest;
import com.example.starter.plan.dto.CloseRequest;
import com.example.starter.plan.dto.CreatePlanRequest;
import com.example.starter.plan.dto.FractionRequest;
import com.example.starter.plan.dto.ReviewRequest;
import com.example.starter.plan.dto.ReviseRequest;
import com.example.starter.plan.dto.VerifyRequest;

import jakarta.validation.Valid;

/**
 * 放疗计划剂量审核与分次执行核对 API。
 */
@RestController
@RequestMapping("/api/plans")
@Validated
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** 创建计划（DESIGN） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreatePlanRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planService.createPlan(req));
    }

    /** 计划换版：新版本 DESIGN，旧版本同事务失效 */
    @PostMapping("/{planId}/revise")
    public Map<String, Object> revise(@PathVariable long planId, @Valid @RequestBody ReviseRequest req) {
        return planService.revisePlan(planId, req);
    }

    /** 物理审核：约束逐项校验 + 总剂量勾稽 */
    @PostMapping("/{planId}/physics-review")
    public Map<String, Object> physicsReview(@PathVariable long planId, @Valid @RequestBody ReviewRequest req) {
        return planService.physicsReview(planId, req);
    }

    /** 医师批准：同一患者同时刻仅一个执行中计划 */
    @PostMapping("/{planId}/approve")
    public Map<String, Object> approve(@PathVariable long planId, @Valid @RequestBody ApproveRequest req) {
        return planService.approve(planId, req);
    }

    /** 首次模体验证：偏差>3%不得执行，恰3%合法 */
    @PostMapping("/{planId}/verify")
    public Map<String, Object> verify(@PathVariable long planId, @Valid @RequestBody VerifyRequest req) {
        return planService.verify(planId, req);
    }

    /** 分次执行：去重、顺序执行、结案后迟到只记事件 */
    @PostMapping("/{planId}/fractions")
    public Map<String, Object> executeFraction(@PathVariable long planId, @Valid @RequestBody FractionRequest req) {
        return planService.executeFraction(planId, req);
    }

    /** 疗程结案：终态，重复结案幂等成功 */
    @PostMapping("/{planId}/close")
    public Map<String, Object> close(@PathVariable long planId, @Valid @RequestBody CloseRequest req) {
        return planService.close(planId, req);
    }

    /** 计划详情 */
    @GetMapping("/{planId}")
    public Map<String, Object> getPlan(@PathVariable long planId) {
        return planService.getPlan(planId);
    }

    /** 审核记录（约束明细可复算） */
    @GetMapping("/{planId}/reviews")
    public List<Map<String, Object>> reviews(@PathVariable long planId) {
        return planService.listReviews(planId);
    }

    /** 模体验证链 */
    @GetMapping("/{planId}/verifications")
    public List<Map<String, Object>> verifications(@PathVariable long planId) {
        return planService.listVerifications(planId);
    }

    /** 分次执行台账 */
    @GetMapping("/{planId}/fractions")
    public List<Map<String, Object>> fractions(@PathVariable long planId) {
        return planService.listFractions(planId);
    }

    /** 迟到分次事件 */
    @GetMapping("/{planId}/late-events")
    public List<Map<String, Object>> lateEvents(@PathVariable long planId) {
        return planService.listLateEvents(planId);
    }

    /** 告警记录 */
    @GetMapping("/{planId}/alerts")
    public List<Map<String, Object>> alerts(@PathVariable long planId) {
        return planService.listAlerts(planId);
    }
}
