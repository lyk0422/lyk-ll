package com.example.starter.web;

import com.example.starter.close.DailyCloseService;
import com.example.starter.odorant.OdorantService;
import com.example.starter.pressure.PressureService;
import com.example.starter.query.CurvePoint;
import com.example.starter.query.PressureChainPoint;
import com.example.starter.query.ReconciliationResult;
import com.example.starter.query.RecomputeService;
import com.example.starter.tank.TankService;
import com.example.starter.valve.LockService;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.DailyCloseResponse;
import com.example.starter.web.dto.InjectionCancelRequest;
import com.example.starter.web.dto.InjectionCancelResponse;
import com.example.starter.web.dto.InjectionIssueRequest;
import com.example.starter.web.dto.InjectionIssueResponse;
import com.example.starter.web.dto.InjectionReceiptRequest;
import com.example.starter.web.dto.InjectionReceiptResponse;
import com.example.starter.web.dto.LockConfirmRequest;
import com.example.starter.web.dto.LockConfirmResponse;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.OdorantDetectResponse;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import com.example.starter.web.dto.ReplenishRequest;
import com.example.starter.web.dto.ReplenishResponse;
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

    public GateStationController(OdorantService odorantService,
                                 PressureService pressureService,
                                 LockService lockService,
                                 DailyCloseService dailyCloseService,
                                 RecomputeService recomputeService,
                                 TankService tankService) {
        this.odorantService = odorantService;
        this.pressureService = pressureService;
        this.lockService = lockService;
        this.dailyCloseService = dailyCloseService;
        this.recomputeService = recomputeService;
        this.tankService = tankService;
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

    // ===== 储罐交接台账 =====

    /**
     * 储罐登记：绑定门站，登记容量、安全储备与三位小数千克期初余额。
     */
    @PostMapping("/tank/register")
    public ResponseEntity<TankRegisterResponse> registerTank(@Valid @RequestBody TankRegisterRequest request) {
        return ResponseEntity.ok(tankService.register(request));
    }

    /**
     * 受控加注计划下达：先预占，低于安全储备则生成待补液事件且不下发。
     */
    @PostMapping("/tank/injection/issue")
    public ResponseEntity<InjectionIssueResponse> issueInjection(
            @Valid @RequestBody InjectionIssueRequest request) {
        return ResponseEntity.ok(tankService.issue(request));
    }

    /**
     * 加注执行回执：预占转实际消耗并释放未用部分。
     */
    @PostMapping("/tank/injection/receipt")
    public ResponseEntity<InjectionReceiptResponse> receiptInjection(
            @Valid @RequestBody InjectionReceiptRequest request) {
        return ResponseEntity.ok(tankService.receipt(request));
    }

    /**
     * 加注计划取消：仅释放尚未执行的预占。
     */
    @PostMapping("/tank/injection/cancel")
    public ResponseEntity<InjectionCancelResponse> cancelInjection(
            @Valid @RequestBody InjectionCancelRequest request) {
        return ResponseEntity.ok(tankService.cancel(request));
    }

    /**
     * 补液入账：唯一运输批号 + 双交接人 + 验收浓度闭区间，原子入账并关闭可满足事件。
     */
    @PostMapping("/tank/replenish")
    public ResponseEntity<ReplenishResponse> replenish(@Valid @RequestBody ReplenishRequest request) {
        return ResponseEntity.ok(tankService.replenish(request));
    }

    /**
     * 储罐台账查询：当前余额/预占/可用/版本与逐笔守恒流水。
     */
    @GetMapping("/tank/status")
    public ResponseEntity<TankStatusResponse> tankStatus(@RequestParam String stationCode) {
        return ResponseEntity.ok(tankService.status(stationCode));
    }
}
