package com.example.starter.api;

import com.example.starter.service.ReplenishService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ATM 加钞计划 REST 入口。编制用 POST 集合资源；其余动作用计划 ID 子资源。
 * 所有写操作均须携带 requestId 以支持幂等重放。
 */
@RestController
@RequestMapping("/api/plans")
public class ReplenishController {

    private final ReplenishService service;

    public ReplenishController(ReplenishService service) {
        this.service = service;
    }

    /** 预测编制：amount 留空按历史日均取现预测。 */
    @PostMapping
    public ResponseEntity<Object> create(@Valid @RequestBody CreatePlanRequest request) {
        return result(service.createPlan(request));
    }

    /** 审批：起算 4 小时押运时限。 */
    @PostMapping("/{planId}/approve")
    public ResponseEntity<Object> approve(@PathVariable long planId, @Valid @RequestBody ApproveRequest request) {
        return result(service.approve(planId, request));
    }

    /** 押运打卡：两名不同押运员先后打卡。 */
    @PostMapping("/{planId}/checkins")
    public ResponseEntity<Object> checkin(@PathVariable long planId, @Valid @RequestBody CheckinRequest request) {
        return result(service.checkin(planId, request));
    }

    /** 双人清点。 */
    @PostMapping("/{planId}/count")
    public ResponseEntity<Object> count(@PathVariable long planId, @Valid @RequestBody CountRequest request) {
        return result(service.count(planId, request));
    }

    /** 设备确认：三方一致核销，否则同事务转长短款挂账。 */
    @PostMapping("/{planId}/device-confirm")
    public ResponseEntity<Object> deviceConfirm(@PathVariable long planId,
                                                @Valid @RequestBody DeviceConfirmRequest request) {
        return result(service.deviceConfirm(planId, request));
    }

    /** 长短款销账：3 个自然日内受理，逾期升级冻结。 */
    @PostMapping("/{planId}/discrepancy/settle")
    public ResponseEntity<Object> settleDiscrepancy(@PathVariable long planId,
                                                    @Valid @RequestBody SettleDiscrepancyRequest request) {
        return result(service.settleDiscrepancy(planId, request));
    }

    /** 日结封存：终态，重复封存幂等成功。 */
    @PostMapping("/{planId}/seal")
    public ResponseEntity<Object> seal(@PathVariable long planId, @Valid @RequestBody SealRequest request) {
        return result(service.seal(planId, request));
    }

    private static ResponseEntity<Object> result(ReplenishService.Outcome outcome) {
        return ResponseEntity.status(outcome.statusCode()).body(outcome.body());
    }

    @GetMapping("/{planId}")
    public PlanResponse get(@PathVariable long planId) {
        return service.getPlan(planId);
    }

    /** 加钞台账（可复算）。 */
    @GetMapping
    public List<PlanResponse> ledger() {
        return service.ledger();
    }

    /** 三方对账复算。 */
    @GetMapping("/{planId}/reconciliation")
    public ReconciliationResponse reconciliation(@PathVariable long planId) {
        return service.reconciliation(planId);
    }

    /** 挂账链复算。 */
    @GetMapping("/{planId}/discrepancy")
    public DiscrepancyResponse discrepancy(@PathVariable long planId) {
        return service.getDiscrepancy(planId);
    }
}
