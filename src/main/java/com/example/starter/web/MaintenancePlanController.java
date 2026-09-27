package com.example.starter.web;

import java.util.List;

import com.example.starter.service.MaintenancePlanService;
import com.example.starter.web.dto.ApiDtos.AttemptDetailView;
import com.example.starter.web.dto.ApiDtos.AttemptDispatchRequest;
import com.example.starter.web.dto.ApiDtos.AttemptView;
import com.example.starter.web.dto.ApiDtos.ChecklistRequest;
import com.example.starter.web.dto.ApiDtos.ConfirmRequest;
import com.example.starter.web.dto.ApiDtos.ObligationView;
import com.example.starter.web.dto.ApiDtos.PlanScanRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 周期维保计划编排接口：扫描义务、派发尝试、清单回报、双人确认与轨迹查询。
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenancePlanController {

    private final MaintenancePlanService planService;

    public MaintenancePlanController(MaintenancePlanService planService) {
        this.planService = planService;
    }

    /** 扫描生成履约义务（requestId 幂等；同一电梯同一到期周期唯一，重复扫描不多派） */
    @PostMapping("/scan")
    public ResponseEntity<String> scan(@Valid @RequestBody PlanScanRequest req) {
        return planService.scan(req);
    }

    /** 派发/续派尝试（requestId 幂等 + expectedVersion 乐观锁） */
    @PostMapping("/obligations/{id}/dispatch")
    public ResponseEntity<String> dispatch(@PathVariable long id,
                                           @Valid @RequestBody AttemptDispatchRequest req) {
        return planService.dispatchAttempt(id, req);
    }

    /** 清单项回报（requestId 幂等 + expectedVersion 乐观锁） */
    @PostMapping("/attempts/{id}/checklist")
    public ResponseEntity<String> checklist(@PathVariable long id,
                                            @Valid @RequestBody ChecklistRequest req) {
        return planService.reportChecklist(id, req);
    }

    /** 完成确认（requestId 幂等 + expectedVersion 乐观锁；须双人共同确认） */
    @PostMapping("/attempts/{id}/confirm")
    public ResponseEntity<String> confirm(@PathVariable long id,
                                          @Valid @RequestBody ConfirmRequest req) {
        return planService.confirm(id, req);
    }

    /** 义务查询（可按电梯过滤） */
    @GetMapping("/obligations")
    public List<ObligationView> obligations(@RequestParam(required = false) Long elevatorId) {
        return planService.obligations(elevatorId);
    }

    @GetMapping("/obligations/{id}")
    public ObligationView obligation(@PathVariable long id) {
        return planService.obligation(id);
    }

    /** 义务下尝试轨迹 */
    @GetMapping("/obligations/{id}/attempts")
    public List<AttemptView> attempts(@PathVariable long id) {
        return planService.attempts(id);
    }

    /** 尝试详情：人员确认与清单轨迹 */
    @GetMapping("/attempts/{id}")
    public AttemptDetailView attemptDetail(@PathVariable long id) {
        return planService.attemptDetail(id);
    }
}
