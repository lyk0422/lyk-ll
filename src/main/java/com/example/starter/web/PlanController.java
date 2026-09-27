package com.example.starter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.dto.PlanDetailResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanOrderReceiveResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionResponse;
import com.example.starter.service.PlanService;

import jakarta.validation.Valid;

/**
 * 日前发电计划版本发布/查询与计划绑定模式限电指令 REST 入口。
 */
@RestController
@RequestMapping("/api/curtail/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** 发布日前计划版本：expectedVersion 乐观校验，成功后版本 +1，同 requestId 幂等。 */
    @PostMapping("/versions")
    public PlanPublishResponse publish(@Valid @RequestBody PlanPublishRequest request) {
        return planService.publish(request);
    }

    /** 受理计划绑定模式限电指令：指定日期、时段、当前版本，受理即冻结机组基数。 */
    @PostMapping("/orders")
    public PlanOrderReceiveResponse receivePlanOrder(@Valid @RequestBody PlanOrderReceiveRequest request) {
        return planService.receivePlanOrder(request);
    }

    /** 版本查询：风场＋UTC 日期的当前版本与全部历史版本。 */
    @GetMapping("/versions")
    public PlanVersionResponse versions(@RequestParam String farmCode,
                                        @RequestParam String planDate) {
        return planService.versions(farmCode, planDate);
    }

    /** 指定版本详情（含 96 段 × 机组核定计划，版本证据）。 */
    @GetMapping("/{planId}")
    public PlanDetailResponse detail(@PathVariable long planId) {
        return planService.detail(planId);
    }
}
