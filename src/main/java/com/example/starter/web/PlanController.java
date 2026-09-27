package com.example.starter.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.dto.PlanDetailResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionView;
import com.example.starter.service.PlanService;

import jakarta.validation.Valid;

/**
 * 日前发电计划 REST 入口：发布（幂等）、当前版本、版本列表与版本详情查询。
 */
@RestController
@RequestMapping("/api/curtail/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /** 发布日前计划：同 requestId 幂等；expectedVersion 乐观校验，不匹配 409。 */
    @PostMapping
    public PlanPublishResponse publish(@Valid @RequestBody PlanPublishRequest request) {
        return planService.publish(request);
    }

    /** 查询风场某日当前计划版本（含 96 段明细）。 */
    @GetMapping("/current")
    public PlanDetailResponse current(@RequestParam String farmCode,
                                      @RequestParam LocalDate planDate) {
        return planService.current(farmCode, planDate);
    }

    /** 查询风场某日全部版本（含历史），按版本号升序。 */
    @GetMapping
    public List<PlanVersionView> versions(@RequestParam String farmCode,
                                          @RequestParam LocalDate planDate) {
        return planService.versions(farmCode, planDate);
    }

    /** 按 id 查询计划版本详情（历史版本亦可查）。 */
    @GetMapping("/{planId}")
    public PlanDetailResponse detail(@PathVariable long planId) {
        return planService.detail(planId);
    }
}
