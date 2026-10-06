package com.example.starter.dimming;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.dimming.Dto.AccidentLockRequest;
import com.example.starter.dimming.Dto.AccidentResponse;
import com.example.starter.dimming.Dto.AccidentUnlockRequest;
import com.example.starter.dimming.Dto.CircuitStateResponse;
import com.example.starter.dimming.Dto.DailySettlementResponse;
import com.example.starter.dimming.Dto.DimCommandRequest;
import com.example.starter.dimming.Dto.DimCommandResponse;
import com.example.starter.dimming.Dto.TelemetryRequest;
import com.example.starter.dimming.Dto.TelemetryResponse;

import jakarta.validation.Valid;

/**
 * 隧道照明调光接口。所有写操作经幂等执行器按 requestId 幂等。
 */
@RestController
@RequestMapping("/api")
@Validated
public class DimmingController {

    private final DimmingService dimmingService;
    private final SettlementService settlementService;
    private final IdempotencyService idempotencyService;

    public DimmingController(DimmingService dimmingService,
            SettlementService settlementService,
            IdempotencyService idempotencyService) {
        this.dimmingService = dimmingService;
        this.settlementService = settlementService;
        this.idempotencyService = idempotencyService;
    }

    /** 分档调光指令 */
    @PostMapping("/circuits/{code}/commands")
    public DimCommandResponse dim(@PathVariable String code,
            @Valid @RequestBody DimCommandRequest request) {
        return idempotencyService.execute("POST /api/circuits/" + code + "/commands",
                request.requestId(), request,
                () -> dimmingService.dim(code, request), DimCommandResponse.class);
    }

    /** 合成检测数据上报，触发亮度/车流量自动规则 */
    @PostMapping("/circuits/{code}/telemetry")
    public TelemetryResponse telemetry(@PathVariable String code,
            @Valid @RequestBody TelemetryRequest request) {
        return idempotencyService.execute("POST /api/circuits/" + code + "/telemetry",
                request.requestId(), request,
                () -> dimmingService.evaluateTelemetry(code, request), TelemetryResponse.class);
    }

    /** 事故告警：锁定在事故模式 */
    @PostMapping("/circuits/{code}/accident/lock")
    public AccidentResponse lockAccident(@PathVariable String code,
            @Valid @RequestBody AccidentLockRequest request) {
        return idempotencyService.execute("POST /api/circuits/" + code + "/accident/lock",
                request.requestId(), request,
                () -> dimmingService.lockAccident(code, request.requestId(), request.alarmId(),
                        request.detail()),
                AccidentResponse.class);
    }

    /** 解除事故锁定：须现场确认 */
    @PostMapping("/circuits/{code}/accident/unlock")
    public AccidentResponse unlockAccident(@PathVariable String code,
            @Valid @RequestBody AccidentUnlockRequest request) {
        return idempotencyService.execute("POST /api/circuits/" + code + "/accident/unlock",
                request.requestId(), request,
                () -> dimmingService.unlockAccident(code, request.requestId(),
                        request.onSiteConfirmed(), request.detail()),
                AccidentResponse.class);
    }

    /** 回路当前状态 */
    @GetMapping("/circuits/{code}")
    public CircuitStateResponse state(@PathVariable String code) {
        return dimmingService.getState(code);
    }

    /** 日结：各回路调光次数与能耗（含可复算分段） */
    @GetMapping("/settlement/daily")
    public DailySettlementResponse dailySettlement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.settle(date);
    }
}
