package com.example.starter.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionResponse;
import com.example.starter.service.PlanService;

import jakarta.validation.Valid;

/**
 * 日前计划 REST 入口：发布（幂等、版本递增）、当前版本与历史版本查询。
 */
@RestController
@RequestMapping("/api/curtail/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** 发布日前计划：整版校验，expectedVersion 匹配当前版本，成功后版本加 1。 */
    @PostMapping
    public PlanPublishResponse publish(@Valid @RequestBody PlanPublishRequest request) {
        return planService.publish(request);
    }

    /** 查询同场同日当前版本（含全部 96 段 × 机组明细）。 */
    @GetMapping("/{farmCode}/{planDate}")
    public PlanVersionResponse current(
            @PathVariable String farmCode,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        return planService.currentPlan(farmCode, planDate);
    }

    /** 查询指定版本（历史版本保留可查）。 */
    @GetMapping("/{farmCode}/{planDate}/versions/{version}")
    public PlanVersionResponse version(
            @PathVariable String farmCode,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate,
            @PathVariable int version) {
        return planService.planVersion(farmCode, planDate, version);
    }
}
