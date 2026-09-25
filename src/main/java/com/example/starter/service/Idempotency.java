package com.example.starter.service;

import com.example.starter.domain.IdempotentRecord;
import com.example.starter.exception.ApiException;
import com.example.starter.repository.IdempotentRepository;
import com.example.starter.web.ServiceResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * requestId 幂等执行器。
 *
 * <p>指纹 = SHA-256(规范化 JSON{requestId + 全部业务字段})。语义：</p>
 * <ul>
 *   <li>同键同参：返回首次结果（HTTP 状态码与响应体原样重放）；</li>
 *   <li>同键异参：409 IDEMPOTENT_CONFLICT，且不执行业务动作、不改变任何状态；</li>
 *   <li>并发同键：先插入占位行借主键唯一索引串行化，后来者等待先到事务结束后重放。</li>
 * </ul>
 *
 * <p>调用方方法须为 {@code @Transactional}：占位行与业务动作同事务，业务异常回滚后占位行一并消失，
 * 后来者可重新占位执行，不会留下卡死的占位行。</p>
 */
@Component
public class Idempotency {

    /** 并发同键等待先到事务完成的最长时间（毫秒）。 */
    private static final long WAIT_TIMEOUT_MS = 15_000L;
    private static final long WAIT_INTERVAL_MS = 20L;

    private final IdempotentRepository repository;
    private final ObjectMapper objectMapper;

    public Idempotency(IdempotentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public ServiceResult execute(String requestId, String requestType, String zoneCode,
                                 Map<String, Object> businessFields, Supplier<ServiceResult> action) {
        String fingerprint = fingerprint(requestId, businessFields);

        IdempotentRecord existing = repository.find(requestId);
        if (existing != null) {
            return replayOrConflict(existing, fingerprint);
        }

        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (true) {
            try {
                repository.insertPlaceholder(requestId, requestType, zoneCode, fingerprint);
                break;
            } catch (DuplicateKeyException duplicate) {
                IdempotentRecord winner = waitForWinner(requestId, deadline);
                if (winner == null) {
                    // 先到事务已回滚（占位行消失），本轮请求重新占位执行
                    continue;
                }
                return replayOrConflict(winner, fingerprint);
            }
        }

        ServiceResult result = action.get();
        try {
            String json = objectMapper.writeValueAsString(result.body());
            repository.saveResponse(requestId, result.httpStatus(), json);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
        return result;
    }

    private IdempotentRecord waitForWinner(String requestId, long deadline) {
        IdempotentRecord record = repository.find(requestId);
        while (record != null && record.getResponseJson() == null
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("CONCURRENT_REQUEST", "并发请求处理被中断");
            }
            record = repository.find(requestId);
        }
        if (record != null && record.getResponseJson() == null) {
            throw ApiException.conflict("CONCURRENT_REQUEST", "同 requestId 并发请求仍在处理中");
        }
        return record;
    }

    private ServiceResult replayOrConflict(IdempotentRecord existing, String fingerprint) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENT_CONFLICT",
                    "requestId=" + existing.getRequestId() + " 已存在但业务参数不一致，拒绝执行");
        }
        if (existing.getResponseJson() == null) {
            throw ApiException.conflict("CONCURRENT_REQUEST", "同 requestId 并发请求仍在处理中");
        }
        try {
            Object body = objectMapper.readValue(existing.getResponseJson(), Object.class);
            return new ServiceResult(existing.getHttpStatus(), body);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 计算指纹：字段按字典序规范化，requestId 与全部业务字段全部纳入。
     */
    public String fingerprint(String requestId, Map<String, Object> businessFields) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("requestId", requestId);
        if (businessFields != null) {
            canonical.putAll(businessFields);
        }
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("指纹规范化失败", e);
        }
    }
}
