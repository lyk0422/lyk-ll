package com.example.starter.museum;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.museum.IdempotencyService.ApiResult;

/**
 * 借展审批与展柜微环境监测 REST 入口。
 * 所有写操作均以 requestId 幂等：指纹覆盖 requestId 与全部业务字段，
 * 同键同参返回首次结果，同键异参返回 409。
 */
@RestController
@RequestMapping("/api")
public class MuseumController {

    /** 借展申请请求。 */
    public record ApplyRequest(String requestId, Long artifactId, Long showcaseId, String borrower,
            LocalDate startDate, LocalDate endDate) {
    }

    /** 点交/恢复复核请求。 */
    public record PartyRequest(String requestId, String party) {
    }

    /** 续展请求。 */
    public record ExtendRequest(String requestId, LocalDate newEndDate) {
    }

    /** 结案请求。 */
    public record CloseRequest(String requestId) {
    }

    /** 微环境读数上报请求。 */
    public record ReadingRequest(String requestId, Instant sampledAt,
            BigDecimal temperature, BigDecimal humidity) {
    }

    /** 工单处置确认请求。 */
    public record ConfirmOrderRequest(String requestId, String handler) {
    }

    /** 点交合成影像特征清单条目。 */
    public record ImageFeatureItem(String code, String value) {
    }

    /** 点交合成影像特征清单登记请求。 */
    public record ImageFeaturesRequest(String requestId, List<ImageFeatureItem> features) {
    }

    private final LoanService loanService;
    private final ClimateService climateService;
    private final ImageHandoverService imageHandoverService;
    private final IdempotencyService idempotencyService;

    public MuseumController(LoanService loanService, ClimateService climateService,
            ImageHandoverService imageHandoverService, IdempotencyService idempotencyService) {
        this.loanService = loanService;
        this.climateService = climateService;
        this.imageHandoverService = imageHandoverService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 受理借展申请。
     */
    @PostMapping("/loans")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody ApplyRequest request) {
        requireIds(request.artifactId(), request.showcaseId());
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(request.artifactId()), String.valueOf(request.showcaseId()),
                request.borrower(), String.valueOf(request.startDate()), String.valueOf(request.endDate()));
        return respond(idempotencyService.execute(request.requestId(), "APPLY", fingerprint,
                () -> new ApiResult(201, loanService.apply(request.requestId(), request.artifactId(),
                        request.showcaseId(), request.borrower(), request.startDate(), request.endDate()))));
    }

    /**
     * 交付点交确认（申请→在展）。
     */
    @PostMapping("/loans/{id}/handover")
    public ResponseEntity<Map<String, Object>> handover(@PathVariable long id,
            @RequestBody PartyRequest request) {
        return confirmHandover(id, "DELIVERY", request);
    }

    /**
     * 归还点交确认（在展→归还点交完成）。
     */
    @PostMapping("/loans/{id}/return-handover")
    public ResponseEntity<Map<String, Object>> returnHandover(@PathVariable long id,
            @RequestBody PartyRequest request) {
        return confirmHandover(id, "RETURN", request);
    }

    private ResponseEntity<Map<String, Object>> confirmHandover(long id, String type, PartyRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(id), type, request.party());
        return respond(idempotencyService.execute(request.requestId(), "HANDOVER_" + type, fingerprint,
                () -> new ApiResult(200,
                        loanService.confirmHandover(id, type, request.party(), request.requestId()))));
    }

    /**
     * 登记点交合成影像特征清单（与点交单同一事务落库）。
     * handoverType 取 DELIVERY 出借点交 / RETURN 归还点交。
     */
    @PostMapping("/loans/{id}/handovers/{handoverType}/images")
    public ResponseEntity<Map<String, Object>> registerImages(@PathVariable long id,
            @PathVariable String handoverType, @RequestBody ImageFeaturesRequest request) {
        if (request.features() == null || request.features().isEmpty()) {
            throw ApiException.badRequest("影像特征清单 features 不能为空");
        }
        String[] parts = new String[request.features().size() * 2 + 3];
        parts[0] = request.requestId();
        parts[1] = String.valueOf(id);
        parts[2] = handoverType;
        int index = 3;
        for (ImageFeatureItem feature : request.features()) {
            parts[index++] = feature.code();
            parts[index++] = feature.value();
        }
        String fingerprint = IdempotencyService.fingerprint(parts);
        List<ImageHandoverService.ImageFeature> features = request.features().stream()
                .map(item -> new ImageHandoverService.ImageFeature(item.code(), item.value()))
                .toList();
        return respond(idempotencyService.execute(request.requestId(),
                "IMAGE_FEATURES_" + handoverType, fingerprint,
                () -> new ApiResult(201,
                        imageHandoverService.registerFeatures(id, handoverType, features))));
    }

    /**
     * 续展审批。
     */
    @PostMapping("/loans/{id}/extend")
    public ResponseEntity<Map<String, Object>> extend(@PathVariable long id,
            @RequestBody ExtendRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(id), String.valueOf(request.newEndDate()));
        return respond(idempotencyService.execute(request.requestId(), "EXTEND", fingerprint,
                () -> new ApiResult(200, loanService.extend(id, request.newEndDate()))));
    }

    /**
     * 结案（终态，重复结案幂等成功）。
     */
    @PostMapping("/loans/{id}/close")
    public ResponseEntity<Map<String, Object>> close(@PathVariable long id,
            @RequestBody CloseRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(), String.valueOf(id));
        return respond(idempotencyService.execute(request.requestId(), "CLOSE", fingerprint,
                () -> new ApiResult(200, loanService.close(id))));
    }

    /**
     * 挂起恢复复核（须双方复核）。
     */
    @PostMapping("/loans/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable long id,
            @RequestBody PartyRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(id), request.party());
        return respond(idempotencyService.execute(request.requestId(), "RESUME", fingerprint,
                () -> new ApiResult(200, loanService.resume(id, request.party()))));
    }

    /**
     * 合同详情。
     */
    @GetMapping("/loans/{id}")
    public Map<String, Object> getLoan(@PathVariable long id) {
        return loanService.get(id);
    }

    /**
     * 文物借展历史（供复算）。
     */
    @GetMapping("/artifacts/{id}/loans")
    public List<Map<String, Object>> loanHistory(@PathVariable long id) {
        return loanService.history(id);
    }

    /**
     * 上报展柜微环境读数。
     */
    @PostMapping("/showcases/{id}/readings")
    public ResponseEntity<Map<String, Object>> ingest(@PathVariable long id,
            @RequestBody ReadingRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(id), String.valueOf(request.sampledAt()),
                String.valueOf(request.temperature()), String.valueOf(request.humidity()));
        return respond(idempotencyService.execute(request.requestId(), "READING", fingerprint,
                () -> new ApiResult(201, climateService.ingest(id, request.sampledAt(),
                        request.temperature(), request.humidity()))));
    }

    /**
     * 展柜越限轨迹（全部读数按采样时刻排序）。
     */
    @GetMapping("/showcases/{id}/readings")
    public List<Map<String, Object>> readings(@PathVariable long id) {
        return climateService.readings(id);
    }

    /**
     * 展柜工单列表（含处置时限）。
     */
    @GetMapping("/showcases/{id}/orders")
    public List<Map<String, Object>> orders(@PathVariable long id) {
        return climateService.orders(id);
    }

    /**
     * 展柜迟到读数事件。
     */
    @GetMapping("/showcases/{id}/late-events")
    public List<Map<String, Object>> lateEvents(@PathVariable long id) {
        return climateService.lateEvents(id);
    }

    /**
     * 工单处置确认。
     */
    @PostMapping("/protection-orders/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirmOrder(@PathVariable long id,
            @RequestBody ConfirmOrderRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(id), request.handler());
        return respond(idempotencyService.execute(request.requestId(), "CONFIRM_ORDER", fingerprint,
                () -> new ApiResult(200, climateService.confirmOrder(id, request.handler()))));
    }

    /**
     * 工单详情。
     */
    @GetMapping("/protection-orders/{id}")
    public Map<String, Object> getOrder(@PathVariable long id) {
        return climateService.getOrder(id);
    }

    /**
     * 闭环影像差异工单（闭环后合同方可结案）。
     */
    @PostMapping("/discrepancy-orders/{id}/close")
    public ResponseEntity<Map<String, Object>> closeDiscrepancyOrder(@PathVariable long id,
            @RequestBody ConfirmOrderRequest request) {
        String fingerprint = IdempotencyService.fingerprint(request.requestId(),
                String.valueOf(id), request.handler());
        return respond(idempotencyService.execute(request.requestId(), "CLOSE_DISCREPANCY", fingerprint,
                () -> new ApiResult(200, imageHandoverService.closeOrder(id, request.handler()))));
    }

    /**
     * 影像差异工单详情（含逐项差异明细）。
     */
    @GetMapping("/discrepancy-orders/{id}")
    public Map<String, Object> getDiscrepancyOrder(@PathVariable long id) {
        return imageHandoverService.getOrder(id);
    }

    /**
     * 合同下影像差异工单列表。
     */
    @GetMapping("/loans/{id}/discrepancy-orders")
    public List<Map<String, Object>> discrepancyOrders(@PathVariable long id) {
        return imageHandoverService.ordersForContract(id);
    }

    /**
     * 触发到期工单升级检查（读数上报时亦自动执行）。
     */
    @PostMapping("/protection-orders/escalate-check")
    public Map<String, Object> escalateCheck() {
        return Map.of("escalated", climateService.escalateDueOrders());
    }

    private ResponseEntity<Map<String, Object>> respond(ApiResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }

    private void requireIds(Long... ids) {
        for (Long id : ids) {
            if (id == null) {
                throw ApiException.badRequest("artifactId 与 showcaseId 不能为空");
            }
        }
    }
}
