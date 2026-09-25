package com.example.starter.reinsurance.service;

import com.example.starter.reinsurance.common.ApiException;
import com.example.starter.reinsurance.common.ErrorCode;
import com.example.starter.reinsurance.domain.IdempotencyRecord;
import com.example.starter.reinsurance.repository.IdempotencyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * requestId 幂等执行器。
 *
 * <p>调用方业务方法必须声明 {@code @Transactional}：占位记录、业务动作、结果回填处于同一事务，
 * 业务失败时整体回滚，不会留下占位垃圾。</p>
 *
 * <p>指纹 = SHA-256(operation | requestId | 规范化业务字段 JSON)：</p>
 * <ul>
 *   <li>同键同参：直接返回首次结果（HTTP 状态码与响应体原样重放）；</li>
 *   <li>同键异参：返回 409，且不执行任何业务动作、不改变账单/账户状态；</li>
 *   <li>同键并发：后到者的占位插入在主键唯一索引上等待首个事务结束，随后重放首次结果或 409。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    /** 首次业务动作的结果。 */
    public record Outcome<T>(int httpStatus, String resultRef, T body) {
        public static <T> Outcome<T> ok(String resultRef, T body) {
            return new Outcome<>(200, resultRef, body);
        }
    }

    /** 幂等执行结果；{@code replay=true} 表示本次为重放首次响应。 */
    public record IdempotentResult(JsonNode body, int httpStatus, String resultRef, boolean replay) {
    }

    private final IdempotencyRepository repository;
    private final ObjectMapper canonicalMapper;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        // Map 键排序，保证同一业务字段集合无论构造顺序如何指纹都一致。
        this.canonicalMapper =
                objectMapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 以 requestId 幂等执行业业动作（必须在调用方事务内调用）。
     *
     * @param requestId 幂等键
     * @param operation 操作类型
     * @param payload 全部业务字段（规范化后参与指纹）
     * @param action 首次执行的业务动作
     */
    public IdempotentResult execute(
            String requestId, String operation, Object payload, Supplier<Outcome<Object>> action) {
        String fingerprint = fingerprint(operation, requestId, payload);
        IdempotencyRecord existing = repository.find(requestId);
        if (existing != null) {
            return resolveExisting(existing, fingerprint);
        }
        boolean acquired;
        try {
            acquired = repository.tryInsertPlaceholder(requestId, operation, fingerprint);
        } catch (DuplicateKeyException e) {
            // 同键并发：首个事务已提交（占位插入在唯一索引上等待其结束），重放或冲突。
            acquired = false;
        }
        if (!acquired) {
            IdempotencyRecord raced = repository.find(requestId);
            if (raced == null) {
                throw new ApiException(
                        ErrorCode.CONCURRENT_MODIFICATION,
                        "同 requestId 并发冲突，请重试: " + requestId);
            }
            return resolveExisting(raced, fingerprint);
        }
        Outcome<Object> outcome = action.get();
        JsonNode jsonBody = objectMapper.valueToTree(outcome.body());
        repository.complete(requestId, outcome.httpStatus(), outcome.resultRef(), jsonBody.toString());
        return new IdempotentResult(jsonBody, outcome.httpStatus(), outcome.resultRef(), false);
    }

    private IdempotentResult resolveExisting(IdempotencyRecord record, String fingerprint) {
        if (!record.getFingerprint().equals(fingerprint)) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "requestId=" + record.getRequestId() + " 已用于不同的业务参数，拒绝执行（409 幂等冲突）");
        }
        if (record.getHttpStatus() == null || record.getHttpStatus() == 0) {
            // 理论上同事务内不可见未提交占位；走到这里说明出现跨事务悬挂占位。
            throw new ApiException(
                    ErrorCode.CONCURRENT_MODIFICATION,
                    "同 requestId 的前序请求尚未完成: " + record.getRequestId());
        }
        try {
            JsonNode node = objectMapper.readTree(record.getResultJson());
            return new IdempotentResult(node, record.getHttpStatus(), record.getResultRef(), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法解析首次结果: requestId=" + record.getRequestId(), e);
        }
    }

    /**
     * 计算指纹：operation、requestId 与全部业务字段的规范化 JSON 均参与哈希。
     */
    public String fingerprint(String operation, String requestId, Object payload) {
        try {
            String canonical =
                    canonicalMapper.writeValueAsString(payload == null ? new Object() : payload);
            String raw = operation + "|" + requestId + "|" + canonical;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("业务字段无法序列化为指纹", e);
        }
    }
}
