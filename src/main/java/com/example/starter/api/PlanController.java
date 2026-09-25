package com.example.starter.api;

import com.example.starter.api.dto.CommandResponse;
import com.example.starter.api.dto.ConfirmCommandRequest;
import com.example.starter.api.dto.DailySettlementResponse;
import com.example.starter.api.dto.DeclarePlanRequest;
import com.example.starter.api.dto.EvaluateHourRequest;
import com.example.starter.api.dto.HourEvaluationResponse;
import com.example.starter.api.dto.IdempotentEnvelope;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReadingBatchRequest;
import com.example.starter.api.dto.ReadingIngestResult;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.command.CommandService;
import com.example.starter.core.ApiException;
import com.example.starter.core.FingerprintHasher;
import com.example.starter.core.IdempotencyService;
import com.example.starter.emission.EmissionService;
import com.example.starter.plan.PlanService;
import com.example.starter.settlement.SettlementService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 掺烧方案全流转入口：申报→审核→执行→读数/小时评估→降掺确认→日结封存。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private static final String SCOPE_DECLARE = "DECLARE_PLAN";
    private static final String SCOPE_CONFIRM = "CONFIRM_COMMAND";

    private final PlanService planService;
    private final EmissionService emissionService;
    private final CommandService commandService;
    private final SettlementService settlementService;
    private final IdempotencyService idempotencyService;
    private final FingerprintHasher fingerprintHasher;

    public PlanController(PlanService planService,
                          EmissionService emissionService,
                          CommandService commandService,
                          SettlementService settlementService,
                          IdempotencyService idempotencyService,
                          FingerprintHasher fingerprintHasher) {
        this.planService = planService;
        this.emissionService = emissionService;
        this.commandService = commandService;
        this.settlementService = settlementService;
        this.idempotencyService = idempotencyService;
        this.fingerprintHasher = fingerprintHasher;
    }

    /** 申报：requestId 幂等，同键同参返回首次结果，同键异参 409。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdempotentEnvelope<PlanResponse> declare(@Valid @RequestBody DeclarePlanRequest request) {
        String fingerprint = fingerprintHasher.sha256(request);
        return idempotencyService.execute(
                SCOPE_DECLARE, request.requestId(), fingerprint, PlanResponse.class,
                () -> planService.declare(request));
    }

    @GetMapping("/{planId}")
    public PlanResponse get(@PathVariable Long planId) {
        return planService.get(planId);
    }

    @PostMapping("/{planId}/review")
    public PlanResponse review(@PathVariable Long planId, @Valid @RequestBody ReviewRequest request) {
        PlanResponse response = planService.review(planId, request);
        // 拒绝终态已在服务事务内提交，提交后再转 422，避免回滚终态。
        if ("REJECTED".equals(response.status())) {
            throw ApiException.rejected("PLAN_REJECTED", response.rejectReason());
        }
        return response;
    }

    @PostMapping("/{planId}/execute")
    public PlanResponse execute(@PathVariable Long planId) {
        return planService.execute(planId);
    }

    @PostMapping("/{planId}/readings")
    public ReadingIngestResult ingest(@PathVariable Long planId,
                                      @Valid @RequestBody ReadingBatchRequest request) {
        return emissionService.ingest(planId, request);
    }

    @PostMapping("/{planId}/evaluations")
    public HourEvaluationResponse evaluate(@PathVariable Long planId,
                                           @Valid @RequestBody EvaluateHourRequest request) {
        return emissionService.evaluate(planId, request.hourStart());
    }

    /** 降掺指令执行确认：requestId 幂等。 */
    @PostMapping("/{planId}/commands/{commandId}/confirm")
    public IdempotentEnvelope<CommandResponse> confirm(@PathVariable Long planId,
                                                       @PathVariable Long commandId,
                                                       @Valid @RequestBody ConfirmCommandRequest request) {
        ConfirmKey key = new ConfirmKey(planId, commandId);
        String fingerprint = fingerprintHasher.sha256(key);
        return idempotencyService.execute(
                SCOPE_CONFIRM, request.requestId(), fingerprint, CommandResponse.class,
                () -> commandService.confirm(planId, commandId, request.requestId()));
    }

    /** 日结封存：终态，重复封存幂等成功。 */
    @PostMapping("/{planId}/settlement")
    public DailySettlementResponse seal(@PathVariable Long planId,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.seal(planId, date);
    }

    /** 确认幂等指纹载体：路径参数属于业务字段，须纳入指纹。 */
    private record ConfirmKey(Long planId, Long commandId) {
    }
}
