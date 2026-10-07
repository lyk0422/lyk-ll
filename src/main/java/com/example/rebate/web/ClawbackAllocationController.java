package com.example.rebate.web;

import com.example.rebate.service.ChargebackIdempotency;
import com.example.rebate.service.ClawbackAllocationService;
import com.example.rebate.service.IdempotentOutcome;
import com.example.rebate.web.dto.ClawbackBatchReceiptRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 汇总到账分配 HTTP 入口：一笔真实银行流水（母凭证）分给同一结案批次内多张已批准冲回单。
 *
 * <p>写操作以 requestId 幂等（业务、版本与幂等结果同事务），指纹覆盖操作类型、requestId、
 * 路径变量、母流水、到账时刻与全部分配行；另有银行流水自然去重：同流水同内容重放首份分配，
 * 异内容 409，且与旧单笔回执流水全局互斥。</p>
 */
@RestController
public class ClawbackAllocationController {

    private final ClawbackAllocationService allocationService;
    private final ChargebackIdempotency idempotency;

    public ClawbackAllocationController(ClawbackAllocationService allocationService,
                                        ChargebackIdempotency idempotency) {
        this.allocationService = allocationService;
        this.idempotency = idempotency;
    }

    /**
     * 汇总分配入账：母流水 + 各子分配一起提交。
     */
    @PostMapping("/api/batches/{batchNo}/clawback-receipts")
    public ResponseEntity<?> allocate(@PathVariable String batchNo,
                                      @Valid @RequestBody ClawbackBatchReceiptRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", req);
        payload.put("batchNo", batchNo);
        IdempotentOutcome outcome = idempotency.run("CLAWBACK_BATCH_RECEIPT", req.requestId(),
                payload, () -> allocationService.allocate(batchNo, req));
        if (outcome.first()) {
            return ResponseEntity.status(outcome.status()).body(outcome.firstBody());
        }
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.replayBody());
    }

    /**
     * 查询汇总母凭证（含分配行与勾稽状态）。
     */
    @GetMapping("/api/batches/{batchNo}/clawback-receipts/{bankSerial}")
    public JsonNode getReceipt(@PathVariable String batchNo, @PathVariable String bankSerial) {
        return allocationService.getReceipt(batchNo, bankSerial);
    }
}
