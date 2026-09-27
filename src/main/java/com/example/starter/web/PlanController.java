package com.example.starter.web;

import java.time.LocalDate;
import java.util.List;

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
import com.example.starter.dto.PlanVersionSummary;
import com.example.starter.service.PlanService;

import jakarta.validation.Valid;

/**
 * 日前计划 REST 入口：发布不可变版本、当前/历史版本查询。
 * 日期参数为 ISO（yyyy-MM-dd），按 UTC 解释。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** 发布日前计划：整版 96 段 × 全场机组，expectedVersion 为本次占用版本号（首版 1，其后当前+1），同 requestId 幂等。 */
    @PostMapping
    public PlanPublishResponse publish(@Valid @RequestBody PlanPublishRequest request) {
        return planService.publish(request);
    }

    /** 同场同日版本列表（含历史，版本号升序）。 */
    @GetMapping("/{farmCode}/{planDate}/versions")
    public List<PlanVersionSummary> listVersions(
            @PathVariable String farmCode,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        return planService.listVersions(farmCode, planDate);
    }

    /** 当前版本详情（含整版 96 段 × 机组明细）。 */
    @GetMapping("/{farmCode}/{planDate}/versions/current")
    public PlanVersionResponse currentVersion(
            @PathVariable String farmCode,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate) {
        return planService.getCurrentVersion(farmCode, planDate);
    }

    /** 指定版本详情（过期版本可查，仅不可被新指令绑定）。 */
    @GetMapping("/{farmCode}/{planDate}/versions/{versionNo}")
    public PlanVersionResponse version(
            @PathVariable String farmCode,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate,
            @PathVariable int versionNo) {
        return planService.getVersion(farmCode, planDate, versionNo);
    }
}
