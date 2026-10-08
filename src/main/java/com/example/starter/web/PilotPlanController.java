package com.example.starter.web;

import jakarta.validation.Valid;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.HandoverService;
import com.example.starter.service.PilotPlanService;
import com.example.starter.web.dto.PlanResponse;
import com.example.starter.web.dto.Requests.ApplyRequest;
import com.example.starter.web.dto.Requests.CancelRequest;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.RescheduleRequest;
import com.example.starter.web.dto.Requests.ScheduleRequest;
import com.example.starter.web.dto.Requests.SettleRequest;
import com.example.starter.web.dto.Requests.StartRequest;

/**
 * 引航计划 REST 入口：申请、编排、确认、执行、核销、取消、改期与实际责任工时查询。
 */
@RestController
@RequestMapping("/api/pilot-plans")
public class PilotPlanController {

    private final PilotPlanService service;
    private final HandoverService handoverService;

    public PilotPlanController(PilotPlanService service, HandoverService handoverService) {
        this.service = service;
        this.handoverService = handoverService;
    }

    @PostMapping("/apply")
    public PlanResponse apply(@Valid @RequestBody ApplyRequest request) {
        return service.apply(request);
    }

    @PostMapping("/schedule")
    public PlanResponse schedule(@Valid @RequestBody ScheduleRequest request) {
        return service.schedule(request);
    }

    @PostMapping("/confirm")
    public PlanResponse confirm(@Valid @RequestBody ConfirmRequest request) {
        return service.confirm(request);
    }

    @PostMapping("/start")
    public PlanResponse start(@Valid @RequestBody StartRequest request) {
        return service.start(request);
    }

    @PostMapping("/settle")
    public PlanResponse settle(@Valid @RequestBody SettleRequest request) {
        return service.settle(request);
    }

    @PostMapping("/cancel")
    public PlanResponse cancel(@Valid @RequestBody CancelRequest request) {
        return service.cancel(request);
    }

    @PostMapping("/reschedule")
    public PlanResponse reschedule(@Valid @RequestBody RescheduleRequest request) {
        return service.reschedule(request);
    }

    /**
     * 查询引航员某自然日（Asia/Shanghai）的实际责任工时与分段。
     * 受控计划按交班责任段计工时，未交班计划沿用整段。
     */
    @GetMapping("/work-hours")
    public HandoverService.WorkHourView workHours(
            @RequestParam long pilotId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return handoverService.workHours(pilotId, day);
    }
}
