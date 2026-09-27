package com.example.starter.cems;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.cems.OverflowTypes.AdjustmentResult;
import com.example.starter.cems.OverflowTypes.CorrectRainfallRequest;
import com.example.starter.cems.OverflowTypes.CreatePermitRequest;
import com.example.starter.cems.OverflowTypes.DailySettlementRequest;
import com.example.starter.cems.OverflowTypes.DailySettlementView;
import com.example.starter.cems.OverflowTypes.DualAverageResult;
import com.example.starter.cems.OverflowTypes.FreezeResult;
import com.example.starter.cems.OverflowTypes.HourDataRequest;
import com.example.starter.cems.OverflowTypes.HourView;
import com.example.starter.cems.OverflowTypes.PermitView;
import com.example.starter.cems.OverflowTypes.RainfallAdjustmentRequest;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionResult;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.UpdatePermitRequest;

/**
 * 雨季溢流证据标记与双口径统计 HTTP 入口（B 题新增）。
 * 所有写操作均携带 requestId 幂等；时刻字段为 UTC ISO-8601（如 2026-09-26T00:00:00）。
 */
@RestController
@RequestMapping("/api/cems/overflow")
public class OverflowController {

    private final OverflowService service;

    public OverflowController(OverflowService service) {
        this.service = service;
    }

    /** 新建首版溢流许可（version=1）。 */
    @PostMapping("/permits")
    public PermitView createPermit(@RequestBody CreatePermitRequest req) {
        return service.createPermit(req);
    }

    /** 携带 expectedVersion 修改许可，成功后版本 +1。 */
    @PostMapping("/permits/update")
    public PermitView updatePermit(@RequestBody UpdatePermitRequest req) {
        return service.updatePermit(req);
    }

    @GetMapping("/permits/{code}")
    public List<PermitView> permits(@PathVariable String code) {
        return service.listPermits(code);
    }

    /** 上报 UTC 整点小时数据（排放浓度＋雨量＋溢流观测）。 */
    @PostMapping("/hours")
    public HourView reportHour(@RequestBody HourDataRequest req) {
        return service.reportHour(req);
    }

    @GetMapping("/hours/{code}")
    public List<HourView> hours(@PathVariable String code, @RequestParam LocalDate dayUtc) {
        return service.listHours(code, dayUtc);
    }

    /** 复核冻结前更正雨量：雨量版本 +1 并重算标记。 */
    @PostMapping("/rainfall/correct")
    public RainfallCorrectionResult correctRainfall(@RequestBody CorrectRainfallRequest req) {
        return service.correctRainfall(req);
    }

    /** 复核冻结：固定许可版本与雨量版本。 */
    @PostMapping("/review/freeze")
    public FreezeResult freeze(@RequestBody ReviewFreezeRequest req) {
        return service.reviewFreeze(req);
    }

    /** 冻结后雨量独立调差：只留差，不改快照。 */
    @PostMapping("/rainfall/adjust")
    public AdjustmentResult adjust(@RequestBody RainfallAdjustmentRequest req) {
        return service.independentAdjustment(req);
    }

    /**
     * 双口径均值查询（半开窗口）。{@code basis=NORMAL} 仅排除证据齐全的合规溢流小时；
     * {@code basis=ALL} 不排除；空集合返回无结果（均值为 null）而非零。
     */
    @GetMapping("/averages/{code}")
    public DualAverageResult averages(
            @PathVariable String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromUtc,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toUtc,
            @RequestParam(defaultValue = "NORMAL") String basis) {
        return service.queryDualAverage(code, fromUtc, toUtc, basis);
    }

    /** 生成/重放某 UTC 日的日结快照（双口径结果随冻结一致）。 */
    @PostMapping("/settlements/daily")
    public DailySettlementView settle(@RequestBody DailySettlementRequest req) {
        return service.settleDaily(req);
    }
}
