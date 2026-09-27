package com.example.starter.web;

import com.example.starter.tank.TankLedgerEntry;
import com.example.starter.tank.TankPlan;
import com.example.starter.tank.TankRefillEvent;
import com.example.starter.tank.TankService;
import com.example.starter.web.dto.TankCancelRequest;
import com.example.starter.web.dto.TankCancelResponse;
import com.example.starter.web.dto.TankPlanRequest;
import com.example.starter.web.dto.TankPlanResponse;
import com.example.starter.web.dto.TankReceiptRequest;
import com.example.starter.web.dto.TankReceiptResponse;
import com.example.starter.web.dto.TankRefillRequest;
import com.example.starter.web.dto.TankRefillResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 储罐交接台账 API。
 *
 * <ul>
 *   <li>POST /api/gate-station/tank/register 储罐登记；</li>
 *   <li>POST /api/gate-station/tank/plan 受控加注计划（预占）；</li>
 *   <li>POST /api/gate-station/tank/plan/receipt 执行回执（预占转消耗）；</li>
 *   <li>POST /api/gate-station/tank/plan/cancel 取消（释放未执行预占）；</li>
 *   <li>POST /api/gate-station/tank/refill 双人确认补液入账；</li>
 *   <li>GET  /api/gate-station/tank | /plans | /ledger | /refill-events 台账查询。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gate-station/tank")
public class TankController {

    private final TankService tankService;

    public TankController(TankService tankService) {
        this.tankService = tankService;
    }

    @PostMapping("/register")
    public ResponseEntity<TankRegisterResponse> register(
            @Valid @RequestBody TankRegisterRequest request) {
        return ResponseEntity.ok(tankService.register(request));
    }

    @PostMapping("/plan")
    public ResponseEntity<TankPlanResponse> createPlan(
            @Valid @RequestBody TankPlanRequest request) {
        return ResponseEntity.ok(tankService.createPlan(request));
    }

    @PostMapping("/plan/receipt")
    public ResponseEntity<TankReceiptResponse> receipt(
            @Valid @RequestBody TankReceiptRequest request) {
        return ResponseEntity.ok(tankService.receipt(request));
    }

    @PostMapping("/plan/cancel")
    public ResponseEntity<TankCancelResponse> cancel(
            @Valid @RequestBody TankCancelRequest request) {
        return ResponseEntity.ok(tankService.cancel(request));
    }

    @PostMapping("/refill")
    public ResponseEntity<TankRefillResponse> refill(
            @Valid @RequestBody TankRefillRequest request) {
        return ResponseEntity.ok(tankService.refill(request));
    }

    /**
     * 储罐台账视图：余额、预占、可用量与版本。
     */
    @GetMapping
    public ResponseEntity<TankView> tank(@RequestParam String tankCode) {
        return ResponseEntity.ok(tankService.tankView(tankCode));
    }

    /**
     * 储罐全部加注计划。
     */
    @GetMapping("/plans")
    public ResponseEntity<List<TankPlan>> plans(@RequestParam String tankCode) {
        return ResponseEntity.ok(tankService.plans(tankCode));
    }

    /**
     * 储罐台账分录（守恒勾稽明细）。
     */
    @GetMapping("/ledger")
    public ResponseEntity<List<TankLedgerEntry>> ledger(@RequestParam String tankCode) {
        return ResponseEntity.ok(tankService.ledger(tankCode));
    }

    /**
     * 储罐待补液事件（含已关闭）。
     */
    @GetMapping("/refill-events")
    public ResponseEntity<List<TankRefillEvent>> refillEvents(@RequestParam String tankCode) {
        return ResponseEntity.ok(tankService.refillEvents(tankCode));
    }
}
