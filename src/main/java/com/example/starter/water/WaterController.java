package com.example.starter.water;

import com.example.starter.common.ApiException;
import com.example.starter.common.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 水质监测与联动处置 REST 入口；全部写操作按 requestId 幂等。
 */
@RestController
@RequestMapping("/api")
public class WaterController {

    private final WaterQualityService waterQualityService;
    private final DisposalService disposalService;
    private final SensorService sensorService;
    private final AssessmentService assessmentService;
    private final WaterRepository repository;
    private final IdempotencyService idempotencyService;

    public WaterController(WaterQualityService waterQualityService,
                           DisposalService disposalService,
                           SensorService sensorService,
                           AssessmentService assessmentService,
                           WaterRepository repository,
                           IdempotencyService idempotencyService) {
        this.waterQualityService = waterQualityService;
        this.disposalService = disposalService;
        this.sensorService = sensorService;
        this.assessmentService = assessmentService;
        this.repository = repository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 采样上报请求；sampledAt 为空时取服务端当前时间。
     */
    public record SampleRequest(@NotBlank String requestId,
                                @NotNull Double dissolvedOxygen,
                                @NotNull Double ammoniaNitrogen,
                                @NotNull Double temperature,
                                Instant sampledAt) {
    }

    /**
     * 展示登记请求。
     */
    public record ExhibitRequest(@NotBlank String requestId, @NotBlank String exhibitName) {
    }

    /**
     * 处置回执确认请求。
     */
    public record ConfirmRequest(@NotBlank String requestId, String confirmedBy) {
    }

    /**
     * 复测上报请求。
     */
    public record RetestRequest(@NotBlank String requestId,
                                @NotNull Double dissolvedOxygen,
                                @NotNull Double ammoniaNitrogen,
                                @NotNull Double temperature) {
    }

    /**
     * 传感器心跳请求。
     */
    public record HeartbeatRequest(@NotBlank String requestId) {
    }

    /**
     * 复连人工确认请求。
     */
    public record ReconnectConfirmRequest(@NotBlank String requestId, @NotBlank String operator) {
    }

    /**
     * 展示池状态视图。
     */
    public record TankView(long id, String code, String name, TankStatus status,
                           boolean displaySuspended, boolean autoDisposalSuspended, boolean dataMissing,
                           int consecutiveViolations, SensorStatus sensorStatus,
                           Instant sensorLastSeenAt) {
    }

    /**
     * 巡检结果视图。
     */
    public record SweepView(List<Long> escalatedOrderIds, List<Long> followUpOrderIds,
                            List<Long> lostSensorTankIds) {
    }

    @PostMapping("/tanks/{tankId}/samples")
    public ResponseEntity<String> submitSample(@PathVariable long tankId,
                                               @Valid @RequestBody SampleRequest request) {
        String fingerprint = IdempotencyService.fingerprint("sample", request.requestId(), tankId,
                request.dissolvedOxygen(), request.ammoniaNitrogen(), request.temperature(),
                request.sampledAt());
        return idempotent(request.requestId(), fingerprint, () -> waterQualityService.submitSample(tankId,
                new WaterQualityService.SampleCommand(request.dissolvedOxygen(),
                        request.ammoniaNitrogen(), request.temperature(), request.sampledAt())));
    }

    @PostMapping("/tanks/{tankId}/exhibits")
    public ResponseEntity<String> registerExhibit(@PathVariable long tankId,
                                                  @Valid @RequestBody ExhibitRequest request) {
        String fingerprint = IdempotencyService.fingerprint("exhibit", request.requestId(), tankId,
                request.exhibitName());
        return idempotent(request.requestId(), fingerprint,
                () -> waterQualityService.registerExhibit(tankId, request.exhibitName()));
    }

    @PostMapping("/disposals/{orderId}/confirm")
    public ResponseEntity<String> confirmDisposal(@PathVariable long orderId,
                                                  @Valid @RequestBody ConfirmRequest request) {
        String fingerprint = IdempotencyService.fingerprint("confirm", request.requestId(), orderId,
                request.confirmedBy());
        IdempotencyService.StoredResponse stored = idempotencyService.executeWithStatus(
                request.requestId(), fingerprint, () -> {
                    DisposalService.ConfirmResult result =
                            disposalService.confirm(orderId, request.confirmedBy());
                    if (result.escalatedNow()) {
                        // 升级已随事务提交，首次结果按 409 返回并被幂等记录。
                        return new IdempotencyService.ActionOutcome(409,
                                java.util.Map.of("error", "处置指令回执恰满确认时限未确认，已升级: " + orderId));
                    }
                    return new IdempotencyService.ActionOutcome(200, result);
                });
        return ResponseEntity.status(stored.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replay", String.valueOf(stored.replayed()))
                .body(stored.body());
    }

    @PostMapping("/tanks/{tankId}/retest")
    public ResponseEntity<String> submitRetest(@PathVariable long tankId,
                                               @Valid @RequestBody RetestRequest request) {
        String fingerprint = IdempotencyService.fingerprint("retest", request.requestId(), tankId,
                request.dissolvedOxygen(), request.ammoniaNitrogen(), request.temperature());
        return idempotent(request.requestId(), fingerprint, () -> waterQualityService.submitRetest(tankId,
                new WaterQualityService.SampleCommand(request.dissolvedOxygen(),
                        request.ammoniaNitrogen(), request.temperature(), null)));
    }

    @PostMapping("/tanks/{tankId}/sensor/heartbeat")
    public ResponseEntity<String> heartbeat(@PathVariable long tankId,
                                            @Valid @RequestBody HeartbeatRequest request) {
        String fingerprint = IdempotencyService.fingerprint("heartbeat", request.requestId(), tankId);
        return idempotent(request.requestId(), fingerprint, () -> sensorService.heartbeat(tankId));
    }

    @PostMapping("/tanks/{tankId}/sensor/reconnect-confirm")
    public ResponseEntity<String> reconnectConfirm(@PathVariable long tankId,
                                                   @Valid @RequestBody ReconnectConfirmRequest request) {
        String fingerprint = IdempotencyService.fingerprint("reconnect-confirm", request.requestId(),
                tankId, request.operator());
        return idempotent(request.requestId(), fingerprint,
                () -> sensorService.reconnectConfirm(tankId, request.operator()));
    }

    @PostMapping("/maintenance/sweep")
    public SweepView sweep() {
        DisposalService.SweepResult disposalResult = disposalService.sweepOverdueOrders();
        List<Long> lostTankIds = sensorService.sweepSensorLoss();
        return new SweepView(disposalResult.escalatedOrderIds(), disposalResult.followUpOrderIds(),
                lostTankIds);
    }

    @GetMapping("/tanks/{tankId}")
    public TankView tankView(@PathVariable long tankId) {
        TankRow tank = repository.findTank(tankId)
                .orElseThrow(() -> ApiException.notFound("展示池不存在: " + tankId));
        return new TankView(tank.id(), tank.code(), tank.name(), tank.status(), tank.displaySuspended(),
                tank.autoDisposalSuspended(), tank.dataMissing(), tank.consecutiveViolations(),
                tank.sensorStatus(), tank.sensorLastSeenAt());
    }

    @GetMapping("/assessment/daily")
    public AssessmentService.DailyAssessment dailyAssessment(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return assessmentService.dailyAssessment(date);
    }

    private ResponseEntity<String> idempotent(String requestId, String fingerprint,
                                              java.util.function.Supplier<Object> action) {
        IdempotencyService.StoredResponse stored = idempotencyService.execute(requestId, fingerprint, action);
        return ResponseEntity.status(stored.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replay", String.valueOf(stored.replayed()))
                .body(stored.body());
    }
}
