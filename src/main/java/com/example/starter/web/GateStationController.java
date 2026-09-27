package com.example.starter.web;

import com.example.starter.close.DailyCloseService;
import com.example.starter.odorant.OdorantService;
import com.example.starter.pressure.PressureService;
import com.example.starter.query.CurvePoint;
import com.example.starter.query.PressureChainPoint;
import com.example.starter.query.ReconciliationResult;
import com.example.starter.query.RecomputeService;
import com.example.starter.tank.InjectionPlanService;
import com.example.starter.tank.RefillService;
import com.example.starter.tank.TankService;
import com.example.starter.valve.LockService;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.DailyCloseResponse;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.LockConfirmRequest;
import com.example.starter.web.dto.LockConfirmResponse;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.OdorantDetectResponse;
import com.example.starter.web.dto.PlanActionResponse;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import com.example.starter.web.dto.RefillRequest;
import com.example.starter.web.dto.RefillResponse;
import com.example.starter.web.dto.TankAdjustRequest;
import com.example.starter.web.dto.TankLedgerEntryView;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankResponse;
import com.example.starter.web.dto.TankStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 城镇燃气门站加臭与压力联动 API。
 *
 * <ul>
 *   <li>POST /api/gate-station/odorant/detect 运行监测：浓度检测与连续越限联锁；</li>
 *   <li>POST /api/gate-station/pressure/detect 压力调节：分级联动调压与比例加注；</li>
 *   <li>POST /api/gate-station/valve/confirm 异常处置：锁阀/复开双人确认；</li>
 *   <li>POST /api/gate-station/daily-close 日结：勾稽与封存。</li>
 *   <li>POST /api/gate-station/tank/register 储罐登记；/tank/adjust 配置调整；</li>
 *   <li>POST /api/gate-station/tank/injection/plan|receipt|cancel 受控加注计划预占/回执/取消；</li>
 *   <li>POST /api/gate-station/tank/refill 补液双人交接入账；</li>
 *   <li>GET  /api/gate-station/tank/status|ledger 储罐状态与台账查询。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gate-station")
public class GateStationController {

    private final OdorantService odorantService;
    private final PressureService pressureService;
    private final LockService lockService;
    private final DailyCloseService dailyCloseService;
    private final RecomputeService recomputeService;
    private final TankService tankService;
    private final InjectionPlanService injectionPlanService;
    private final RefillService refillService;

    public GateStationController(OdorantService odorantService,
                                 PressureService pressureService,
                                 LockService lockService,
                                 DailyCloseService dailyCloseService,
                                 RecomputeService recomputeService,
                                 TankService tankService,
                                 InjectionPlanService injectionPlanService,
                                 RefillService refillService) {
        this.odorantService = odorantService;
        this.pressureService = pressureService;
        this.lockService = lockService;
        this.dailyCloseService = dailyCloseService;
        this.recomputeService = recomputeService;
        this.tankService = tankService;
        this.injectionPlanService = injectionPlanService;
        this.refillService = refillService;
    }

    @PostMapping("/odorant/detect")
    public ResponseEntity<OdorantDetectResponse> detectOdorant(@Valid @RequestBody OdorantDetectRequest request) {
        return ResponseEntity.ok(odorantService.detect(request));
    }

    @PostMapping("/pressure/detect")
    public ResponseEntity<PressureDetectResponse> detectPressure(@Valid @RequestBody PressureDetectRequest request) {
        return ResponseEntity.ok(pressureService.detect(request));
    }

    @PostMapping("/valve/confirm")
    public ResponseEntity<LockConfirmResponse> confirmLock(@Valid @RequestBody LockConfirmRequest request) {
        return ResponseEntity.ok(lockService.confirm(request));
    }

    @PostMapping("/daily-close")
    public ResponseEntity<DailyCloseResponse> dailyClose(@Valid @RequestBody DailyCloseRequest request) {
        return ResponseEntity.ok(dailyCloseService.close(request));
    }

    /**
     * 复算浓度曲线：按门站编码与业务日期返回逐点闭区间重新判定结果。
     */
    @GetMapping("/recompute/concentration-curve")
    public ResponseEntity<List<CurvePoint>> concentrationCurve(
            @RequestParam String stationCode, @RequestParam LocalDate bizDate) {
        return ResponseEntity.ok(recomputeService.concentrationCurve(stationCode, bizDate));
    }

    /**
     * 复算压力链：逐点重新分级并回查调压/加注原子指令。
     */
    @GetMapping("/recompute/pressure-chain")
    public ResponseEntity<List<PressureChainPoint>> pressureChain(
            @RequestParam String stationCode, @RequestParam LocalDate bizDate) {
        return ResponseEntity.ok(recomputeService.pressureChain(stationCode, bizDate));
    }

    /**
     * 复算日结消耗勾稽：累计流量、理论消耗、偏差与封存态全部由原始数据重算。
     */
    @GetMapping("/recompute/reconciliation")
    public ResponseEntity<ReconciliationResult> reconciliation(
            @RequestParam String stationCode, @RequestParam LocalDate bizDate) {
        return ResponseEntity.ok(recomputeService.reconcile(stationCode, bizDate));
    }

    @PostMapping("/tank/register")
    public ResponseEntity<TankResponse> registerTank(@Valid @RequestBody TankRegisterRequest request) {
        return ResponseEntity.ok(tankService.register(request));
    }

    @PostMapping("/tank/adjust")
    public ResponseEntity<TankResponse> adjustTank(@Valid @RequestBody TankAdjustRequest request) {
        return ResponseEntity.ok(tankService.adjust(request));
    }

    @PostMapping("/tank/injection/plan")
    public ResponseEntity<InjectionPlanResponse> planInjection(
            @Valid @RequestBody InjectionPlanRequest request) {
        return ResponseEntity.ok(injectionPlanService.plan(request));
    }

    @PostMapping("/tank/injection/receipt")
    public ResponseEntity<PlanActionResponse> receiptInjection(
            @Valid @RequestBody InjectionReceiptRequest request) {
        return ResponseEntity.ok(injectionPlanService.receipt(request));
    }

    @PostMapping("/tank/injection/cancel")
    public ResponseEntity<PlanActionResponse> cancelInjection(
            @Valid @RequestBody InjectionCancelRequest request) {
        return ResponseEntity.ok(injectionPlanService.cancel(request));
    }

    @PostMapping("/tank/refill")
    public ResponseEntity<RefillResponse> confirmRefill(@Valid @RequestBody RefillRequest request) {
        return ResponseEntity.ok(refillService.confirm(request));
    }

    /**
     * 储罐状态查询：余额、预占、可用量、版本与待补液事件。
     */
    @GetMapping("/tank/status")
    public ResponseEntity<TankStatusResponse> tankStatus(@RequestParam String stationCode) {
        return ResponseEntity.ok(tankService.status(stationCode));
    }

    /**
     * 储罐台账查询：按业务日期返回余额/预占变动守恒明细。
     */
    @GetMapping("/tank/ledger")
    public ResponseEntity<List<TankLedgerEntryView>> tankLedger(
            @RequestParam String stationCode, @RequestParam LocalDate bizDate) {
        return ResponseEntity.ok(tankService.ledger(stationCode, bizDate));
    }
}
