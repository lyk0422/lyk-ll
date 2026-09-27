package com.example.starter.web;

import com.example.starter.tank.InjectionPlan;
import com.example.starter.tank.InjectionPlanService;
import com.example.starter.tank.RefillEvent;
import com.example.starter.tank.RefillService;
import com.example.starter.tank.TankLedgerEntry;
import com.example.starter.tank.TankService;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionCancelResponse;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.InjectionReceiptResponse;
import com.example.starter.web.dto.RefillConfirmRequest;
import com.example.starter.web.dto.RefillConfirmResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankRegisterResponse;
import com.example.starter.web.dto.TankStatusResponse;
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
 * 加臭剂储罐安全储备与补液交接 API。
 *
 * <ul>
 *   <li>POST /api/gate-station/tank/register 储罐登记（版本从 1 开始）；</li>
 *   <li>POST /api/gate-station/injection/plan 受控加注计划：先预占，触及安全储备则
 *       生成待补液事件且不下发加注；</li>
 *   <li>POST /api/gate-station/injection/receipt 执行回执：预占转消耗并释放未用部分；</li>
 *   <li>POST /api/gate-station/injection/cancel 取消：只释放尚未执行的预占；</li>
 *   <li>POST /api/gate-station/refill/confirm 补液交接：双人确认、浓度闭区间、
 *       容量上限门禁，原子入账并关闭可满足的待补液事件；</li>
 *   <li>GET 查询：储罐状态、台账明细、补液事件、加注计划。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gate-station")
public class TankController {

    private final TankService tankService;
    private final InjectionPlanService injectionPlanService;
    private final RefillService refillService;

    public TankController(TankService tankService,
                          InjectionPlanService injectionPlanService,
                          RefillService refillService) {
        this.tankService = tankService;
        this.injectionPlanService = injectionPlanService;
        this.refillService = refillService;
    }

    @PostMapping("/tank/register")
    public ResponseEntity<TankRegisterResponse> registerTank(
            @Valid @RequestBody TankRegisterRequest request) {
        return ResponseEntity.ok(tankService.register(request));
    }

    @PostMapping("/injection/plan")
    public ResponseEntity<InjectionPlanResponse> plan(
            @Valid @RequestBody InjectionPlanRequest request) {
        return ResponseEntity.ok(injectionPlanService.plan(request));
    }

    @PostMapping("/injection/receipt")
    public ResponseEntity<InjectionReceiptResponse> receipt(
            @Valid @RequestBody InjectionReceiptRequest request) {
        return ResponseEntity.ok(injectionPlanService.receipt(request));
    }

    @PostMapping("/injection/cancel")
    public ResponseEntity<InjectionCancelResponse> cancel(
            @Valid @RequestBody InjectionCancelRequest request) {
        return ResponseEntity.ok(injectionPlanService.cancel(request));
    }

    @PostMapping("/refill/confirm")
    public ResponseEntity<RefillConfirmResponse> confirmRefill(
            @Valid @RequestBody RefillConfirmRequest request) {
        return ResponseEntity.ok(refillService.confirm(request));
    }

    /**
     * 储罐状态：余额、预占、可用量、版本与待补液事件数。
     */
    @GetMapping("/tank/status")
    public ResponseEntity<TankStatusResponse> status(@RequestParam String stationCode) {
        return ResponseEntity.ok(tankService.status(stationCode));
    }

    /**
     * 台账明细：余额、预占、补液与消耗的守恒勾稽依据。
     */
    @GetMapping("/tank/ledger")
    public ResponseEntity<List<TankLedgerEntry>> ledger(@RequestParam String stationCode) {
        return ResponseEntity.ok(tankService.ledger(stationCode));
    }

    /**
     * 补液事件：待补液与已关闭事件全量。
     */
    @GetMapping("/tank/refill-events")
    public ResponseEntity<List<RefillEvent>> refillEvents(@RequestParam String stationCode) {
        return ResponseEntity.ok(tankService.refillEvents(stationCode));
    }

    /**
     * 加注计划：预占、已回执、已取消与未下发计划全量。
     */
    @GetMapping("/injection/plans")
    public ResponseEntity<List<InjectionPlan>> plans(@RequestParam String stationCode) {
        return ResponseEntity.ok(tankService.plans(stationCode));
    }
}
