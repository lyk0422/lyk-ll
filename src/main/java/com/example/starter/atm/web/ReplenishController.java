package com.example.starter.atm.web;

import com.example.starter.atm.service.ReplenishService;
import com.example.starter.atm.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ATM 加钞计划 REST 入口骨架。
 */
@RestController
@RequestMapping("/api")
public class ReplenishController {

    private final ReplenishService service;

    public ReplenishController(ReplenishService service) {
        this.service = service;
    }

    @PostMapping("/plans/prepare")
    public PlanResponse prepare(@Valid @RequestBody PreparePlanRequest req) {
        return service.prepare(req);
    }

    @PostMapping("/plans/{planNo}/approve")
    public PlanResponse approve(@PathVariable String planNo, @Valid @RequestBody ApproveRequest req) {
        return service.approve(planNo, req);
    }

    @PostMapping("/plans/{planNo}/escort/checkins")
    public CheckinResponse checkin(@PathVariable String planNo, @Valid @RequestBody CheckinRequest req) {
        return service.checkin(planNo, req);
    }

    @PostMapping("/plans/{planNo}/count")
    public PlanResponse count(@PathVariable String planNo, @Valid @RequestBody CountRequest req) {
        return service.count(planNo, req);
    }

    @PostMapping("/plans/{planNo}/device-confirm")
    public PlanResponse deviceConfirm(@PathVariable String planNo,
                                      @Valid @RequestBody DeviceConfirmRequest req) {
        return service.deviceConfirm(planNo, req);
    }

    @PostMapping("/plans/{planNo}/overshort/settle")
    public PlanResponse settleOverShort(@PathVariable String planNo,
                                        @Valid @RequestBody SettleOverShortRequest req) {
        return service.settleOverShort(planNo, req);
    }

    @PostMapping("/plans/{planNo}/seal")
    public PlanResponse seal(@PathVariable String planNo, @Valid @RequestBody SealRequest req) {
        return service.seal(planNo, req);
    }

    @GetMapping("/plans/{planNo}")
    public PlanResponse getPlan(@PathVariable String planNo) {
        return service.getPlan(planNo);
    }

    @GetMapping("/plans")
    public List<PlanResponse> ledger() {
        return service.ledger();
    }

    @GetMapping("/overshorts")
    public List<OverShortResponse> overShorts() {
        return service.overShorts();
    }

    @PostMapping("/escort/sweep")
    public Map<String, Object> sweep() {
        return Map.of("abnormalPlans", service.sweepEscortTimeouts());
    }
}
