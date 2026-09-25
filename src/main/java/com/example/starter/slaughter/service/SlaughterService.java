package com.example.starter.slaughter.service;

import com.example.starter.slaughter.dto.CheckoutRequest;
import com.example.starter.slaughter.dto.CheckoutResponse;
import com.example.starter.slaughter.dto.DeclareRequest;
import com.example.starter.slaughter.dto.DeclareResponse;
import com.example.starter.slaughter.dto.InspectionCompleteRequest;
import com.example.starter.slaughter.dto.InspectionRegisterRequest;
import com.example.starter.slaughter.dto.InspectionResponse;
import com.example.starter.slaughter.dto.JudgmentRequest;
import com.example.starter.slaughter.dto.JudgmentResponse;
import com.example.starter.slaughter.dto.PreQuarantineRequest;
import com.example.starter.slaughter.dto.PreQuarantineResponse;
import com.example.starter.slaughter.dto.ReceiptRequest;
import com.example.starter.slaughter.dto.ReceiptResponse;
import com.example.starter.slaughter.dto.ReconciliationResponse;
import com.example.starter.slaughter.persistence.IdempotentRecord;
import com.example.starter.slaughter.persistence.SlaughterRepository;
import com.example.starter.slaughter.support.FingerprintCalculator;
import com.example.starter.slaughter.web.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 屠宰检疫业务编排（无事务）：委托 {@link SlaughterTxOps} 在事务内完成业务与幂等占位，
 * 本层负责并发 requestId 仲裁——同键同参重放首次结果、同键异参 409 且不改变批次状态。
 */
@Service
public class SlaughterService {

    /** 宰前申报有效期：6 小时（UTC），恰满即失效。 */
    public static final long VALID_WINDOW_SECONDS = 6 * 3600L;

    /** 并发占位冲突时等待胜者事务提交的最长轮询时间（毫秒）。 */
    private static final long CLAIM_WAIT_MILLIS = 10_000L;
    private static final long CLAIM_POLL_MILLIS = 20L;

    private final SlaughterTxOps txOps;
    private final SlaughterRepository repository;
    private final FingerprintCalculator fingerprints;
    private final ObjectMapper objectMapper;

    public SlaughterService(SlaughterTxOps txOps,
                            SlaughterRepository repository,
                            FingerprintCalculator fingerprints,
                            ObjectMapper objectMapper) {
        this.txOps = txOps;
        this.repository = repository;
        this.fingerprints = fingerprints;
        this.objectMapper = objectMapper;
    }

    public ServiceOutcome<DeclareResponse> declare(DeclareRequest request) {
        return runWithIdempotency(request.requestId(), IdempotentOperation.DECLARE,
                Map.of("batchNo", request.batchNo(), "headNos", request.headNos()),
                () -> txOps.declare(request), DeclareResponse.class);
    }

    public ServiceOutcome<PreQuarantineResponse> preQuarantine(String batchNo, PreQuarantineRequest request) {
        return runWithIdempotency(request.requestId(), IdempotentOperation.PRE_QUARANTINE,
                Map.of("batchNo", batchNo, "result", request.result().name()),
                () -> txOps.preQuarantine(batchNo, request), PreQuarantineResponse.class);
    }

    public ServiceOutcome<InspectionResponse> registerInspection(String batchNo,
                                                                 InspectionRegisterRequest request) {
        return runWithIdempotency(request.requestId(), IdempotentOperation.INSPECTION_REGISTER,
                Map.of("batchNo", batchNo,
                        "items", request.items().stream()
                                .map(i -> Map.of("headNo", i.headNo(), "result", i.result().name())).toList()),
                () -> txOps.registerInspection(batchNo, request), InspectionResponse.class);
    }

    public ServiceOutcome<InspectionResponse> completeInspection(String batchNo,
                                                                 InspectionCompleteRequest request) {
        return runWithIdempotency(request.requestId(), IdempotentOperation.INSPECTION_COMPLETE,
                Map.of("batchNo", batchNo),
                () -> txOps.completeInspection(batchNo, request), InspectionResponse.class);
    }

    public ServiceOutcome<JudgmentResponse> judge(String batchNo, JudgmentRequest request) {
        return runWithIdempotency(request.requestId(), IdempotentOperation.JUDGMENT,
                Map.of("batchNo", batchNo, "items", request.items().stream().map(i -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("headNo", i.headNo());
                    m.put("category", i.category().name());
                    m.put("limitedRegion", i.limitedRegion());
                    return m;
                }).toList()),
                () -> txOps.judge(batchNo, request), JudgmentResponse.class);
    }

    public ServiceOutcome<ReceiptResponse> registerReceipt(String batchNo, ReceiptRequest request) {
        return runWithIdempotency(request.requestId(), IdempotentOperation.RECEIPT,
                Map.of("batchNo", batchNo, "receiptNo", request.receiptNo(),
                        "headNos", request.headNos()),
                () -> txOps.registerReceipt(batchNo, request), ReceiptResponse.class);
    }

    public ServiceOutcome<CheckoutResponse> checkout(String batchNo, CheckoutRequest request) {
        return runWithIdempotency(request.requestId(), IdempotentOperation.CHECKOUT,
                Map.of("batchNo", batchNo),
                () -> txOps.checkout(batchNo, request), CheckoutResponse.class);
    }

    /** 只读复算头只台账、判定明细、回执勾稽与库存标识。 */
    public ReconciliationResponse reconcile(String batchNo) {
        return txOps.reconcile(batchNo);
    }

    /**
     * 执行事务操作；若事务内抢占位失败（{@link ConcurrentClaimException}），等待胜者提交：
     * 同指纹重放首次结果，异指纹返回 409；胜者回滚（占位消失）则重试本请求。
     */
    private <T> ServiceOutcome<T> runWithIdempotency(String requestId, IdempotentOperation operation,
                                                     Map<String, Object> fields,
                                                     Supplier<ServiceOutcome<T>> action,
                                                     Class<T> responseType) {
        String fingerprint = fingerprints.calculate(operation.name(), requestId, fields);
        long deadline = System.currentTimeMillis() + CLAIM_WAIT_MILLIS;
        while (true) {
            try {
                return action.get();
            } catch (ConcurrentClaimException claim) {
                while (System.currentTimeMillis() < deadline) {
                    Optional<IdempotentRecord> winner = repository.findIdempotent(requestId);
                    if (winner.isPresent() && !winner.get().responseBody().isEmpty()) {
                        IdempotentRecord record = winner.get();
                        if (!Objects.equals(record.fingerprint(), fingerprint)) {
                            throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENT_CONFLICT",
                                    "requestId 已用于不同业务参数的请求：" + requestId);
                        }
                        return new ServiceOutcome<>(
                                deserialize(record.responseBody(), responseType), true, record.httpStatus());
                    }
                    if (winner.isEmpty()) {
                        // 胜者事务回滚，占位已消失，重新尝试受理。
                        break;
                    }
                    try {
                        Thread.sleep(CLAIM_POLL_MILLIS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    throw new BusinessException(HttpStatus.CONFLICT, "IDEMPOTENT_RACE",
                            "并发幂等处理等待超时：" + requestId);
                }
            }
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }
}
