package com.example.starter.rights.service;

import com.example.starter.rights.ApiException;
import com.example.starter.rights.AppClock;
import com.example.starter.rights.repo.RequestLedgerRepository;
import com.example.starter.rights.repo.RequestLedgerRepository.LedgerEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 写请求幂等执行器：同 requestId 同指纹返回首次结果，异指纹 409。
 *
 * <p>流程（全部处于调用方业务事务内，业务回滚则占位一并回滚）：
 * <ol>
 *   <li>先查台账：已完成且同指纹 → 重放首次结果；异指纹 → 409；</li>
 *   <li>插入占位行（request_id 唯一索引）：并发同键请求会在数据库上排队，
 *       先提交者赢，另一个收到唯一键冲突后走重放/409 分支；</li>
 *   <li>执行业务，成功后在同一事务内写回响应快照。</li>
 * </ol>
 */
@Component
public class IdempotentExecutor {

    private final RequestLedgerRepository ledger;
    private final AppClock clock;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(RequestLedgerRepository ledger, AppClock clock, ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹（SHA-256），覆盖请求类型与规范化后的参数体
     * （授权版本、窗口、排期集合、预期版本等均在参数体内）。
     */
    public String fingerprint(String requestType, Object payload) {
        String canonical;
        try {
            canonical = requestType + "|" + objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化请求体用于指纹计算", e);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 在幂等保护下执行业务。
     */
    public <T> T execute(String requestId, String requestType, String fingerprint,
                         Class<T> resultType, Supplier<T> action) {
        var existing = ledger.find(requestId);
        if (existing.isPresent()) {
            return replayOrReject(requestId, fingerprint, existing.get(), resultType);
        }
        try {
            ledger.insertPending(requestId, requestType, fingerprint, clock.now());
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一索引行锁在对方事务结束后才放行，因此这里读到的是终态。
            LedgerEntry winner = ledger.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE", "请求处理中，请稍后重试"));
            return replayOrReject(requestId, fingerprint, winner, resultType);
        }
        T result = action.get();
        ledger.complete(requestId, 200, toJson(result));
        return result;
    }

    private <T> T replayOrReject(String requestId, String fingerprint, LedgerEntry entry, Class<T> resultType) {
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId=" + requestId + " 已用于不同参数的请求");
        }
        if (!entry.completed()) {
            throw ApiException.conflict("IDEMPOTENCY_PENDING", "请求处理中，请稍后重试");
        }
        try {
            return objectMapper.readValue(entry.responseJson(), resultType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
