package com.example.rebate.web;

import com.example.rebate.service.ChargebackIdempotency;
import com.example.rebate.service.ChargebackService;
import com.example.rebate.service.IdempotentOutcome;
import com.example.rebate.service.ServiceResult;
import com.example.rebate.web.dto.ChargebackBalanceView;
import com.example.rebate.web.dto.ChargebackReviewRequest;
import com.example.rebate.web.dto.ChargebackSubmitRequest;
import com.example.rebate.web.dto.ChargebackView;
import com.example.rebate.web.dto.ClawbackReceiptRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结案后分批冲回 HTTP 入口。
 *
 * <p>冲回提交 → 复核（批准/驳回）→ 追缴到账回执，并提供逐单余额与冲回单查询。
 * 写操作以 requestId 幂等（业务、版本与幂等结果同事务），指纹覆盖操作类型、requestId、
 * 路径变量、expectedVersion、全部集合字段与回执精确时刻；不改动原批次及其结案状态。</p>
 */
@RestController
public class ChargebackController {

    private final ChargebackService chargebackService;
    private final ChargebackIdempotency idempotency;

    public ChargebackController(ChargebackService chargebackService,
                                ChargebackIdempotency idempotency) {
        this.chargebackService = chargebackService;
        this.idempotency = idempotency;
    }

    /**
     * 提交冲回单（初为待复核）。
     */
    @PostMapping("/api/batches/{batchNo}/chargebacks")
    public ResponseEntity<?> submit(@PathVariable String batchNo,
                                    @Valid @RequestBody ChargebackSubmitRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotent("CHARGEBACK_SUBMIT", req.requestId(), payload,
                () -> ServiceResult.ok(chargebackService.submit(batchNo, req)));
    }

    /**
     * 复核冲回单：批准（占用余额并产生追缴明细）或驳回（不占额）。
     */
    @PostMapping("/api/batches/{batchNo}/chargebacks/{correctionNo}/review")
    public ResponseEntity<?> review(@PathVariable String batchNo,
                                    @PathVariable String correctionNo,
                                    @Valid @RequestBody ChargebackReviewRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "correctionNo", correctionNo);
        return idempotent("CHARGEBACK_REVIEW", req.requestId(), payload,
                () -> ServiceResult.ok(chargebackService.review(batchNo, correctionNo, req)));
    }

    /**
     * 登记追缴到账回执（可多笔部分到账）。
     */
    @PostMapping("/api/batches/{batchNo}/chargebacks/{correctionNo}/receipts")
    public ResponseEntity<?> receipt(@PathVariable String batchNo,
                                     @PathVariable String correctionNo,
                                     @Valid @RequestBody ClawbackReceiptRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "correctionNo", correctionNo);
        return idempotent("CLAWBACK_RECEIPT", req.requestId(), payload,
                () -> ServiceResult.ok(
                        chargebackService.registerReceipt(batchNo, correctionNo, req)));
    }

    /**
     * 查询单笔冲回单（含余额、追缴明细与回执）。
     */
    @GetMapping("/api/batches/{batchNo}/chargebacks/{correctionNo}")
    public ChargebackView getOrder(@PathVariable String batchNo,
                                   @PathVariable String correctionNo) {
        return chargebackService.getOrder(batchNo, correctionNo);
    }

    /**
     * 逐单冲回余额查询。
     */
    @GetMapping("/api/batches/{batchNo}/chargebacks")
    public List<ChargebackBalanceView> balances(@PathVariable String batchNo) {
        return chargebackService.balances(batchNo);
    }

    private ResponseEntity<?> idempotent(String opType, String requestId, Object payload,
                                         java.util.function.Supplier<ServiceResult> action) {
        IdempotentOutcome outcome = idempotency.run(opType, requestId, payload, action);
        if (outcome.first()) {
            return ResponseEntity.status(outcome.status()).body(outcome.firstBody());
        }
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.replayBody());
    }

    private Map<String, Object> payload(Object body, Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("body", body);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
