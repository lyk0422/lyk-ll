package com.example.starter.cems;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等支持：指纹覆盖 requestId 与全部业务字段，同键同参回放首次结果，同键异参由调用方抛出 409。
 * 旧入口沿用 toString 规范化指纹（行为不变）；雨季溢流新入口使用结构化 JSON 指纹，
 * 合法文本含分隔符（|、=、,、{}）也能无歧义区分，且覆盖版本、集合与精确时刻。
 */
@Component
public class IdempotencySupport {

    private final ObjectMapper objectMapper;
    private final CemsRepository repo;
    private final CemsClock clock;

    public IdempotencySupport(ObjectMapper objectMapper, CemsRepository repo, CemsClock clock) {
        this.objectMapper = objectMapper;
        this.repo = repo;
        this.clock = clock;
    }

    /** 计算 SHA-256 指纹：requestId + 按 key 排序后的业务字段（旧入口沿用，行为不变）。 */
    public String fingerprint(String requestId, Map<String, ?> businessFields) {
        Map<String, Object> sorted = new TreeMap<>();
        if (businessFields != null) {
            sorted.putAll(businessFields);
        }
        String canonical = "requestId=" + nullToEmpty(requestId) + "|" + sorted;
        return sha256(canonical);
    }

    /**
     * 结构化指纹：业务字段按键名排序后 JSON 序列化再取 SHA-256。
     * JSON 对字符串、数字、布尔、null 与集合（按序）均有明确边界，
     * 合法文本含分隔符也须区分（如 {a:"x|y"} 与 {a:"x", b:"y"} 指纹不同）。
     */
    public String structuredFingerprint(String requestId, Map<String, ?> businessFields) {
        Map<String, Object> sorted = new TreeMap<>();
        if (businessFields != null) {
            sorted.putAll(businessFields);
        }
        try {
            String canonical = objectMapper.writeValueAsString(sorted);
            return sha256("v2|requestId=" + nullToEmpty(requestId) + "|" + canonical);
        } catch (Exception e) {
            throw new IllegalStateException("结构化指纹序列化失败", e);
        }
    }

    /**
     * 幂等执行：指纹覆盖 requestId 与全部业务字段。
     * 同键同参返回首次结果；同键异参抛 409 且不执行动作、不改变业务状态。
     * 先插入占位行（主键串行化同键并发），业务动作成功后回写结果；动作失败则随事务回滚释放该键。
     * 业务写入、版本变更与幂等结果回写在调用方同一事务内完成。
     *
     * @param structured true 使用结构化 JSON 指纹（新入口）；false 沿用旧规范化指纹（旧入口行为不变）
     */
    public <T> T execute(String requestId, String requestType, Map<String, Object> fields,
                         Class<T> resultType, Supplier<T> action, boolean structured) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        String fingerprint = structured
                ? structuredFingerprint(requestId, fields) : fingerprint(requestId, fields);
        Map<String, Object> existing = repo.findIdempotent(requestId);
        if (existing != null) {
            return replayOrConflict(requestId, fingerprint, existing, resultType);
        }
        while (true) {
            if (repo.insertIdempotent(requestId, requestType, fingerprint, "", clock.now())) {
                T result = action.get();
                repo.updateIdempotentResult(requestId, toJson(result));
                return result;
            }
            // 同键并发：等待首个事务提交结果；若其回滚（占位行消失）则重新抢占。
            Map<String, Object> raced = waitForIdempotentResult(requestId);
            if (raced != null) {
                return replayOrConflict(requestId, fingerprint, raced, resultType);
            }
        }
    }

    private <T> T replayOrConflict(String rid, String fingerprint, Map<String, Object> row, Class<T> resultType) {
        if (!fingerprint.equals(row.get("fingerprint"))) {
            throw new ConflictException("requestId=" + rid + " 请求参数与首次调用不一致（409 幂等冲突）");
        }
        return fromJson((String) row.get("result_json"), resultType);
    }

    /**
     * 并发同键后等待首个事务提交结果（真实并发协调，带超时）。
     * @return 已回写结果的幂等行；占位行消失（首个事务回滚）时返回 null 供调用方重新抢占
     */
    private Map<String, Object> waitForIdempotentResult(String rid) {
        long deadlineMillis = System.currentTimeMillis() + 15_000L;
        while (true) {
            Map<String, Object> row = repo.findIdempotent(rid);
            if (row == null) {
                return null;
            }
            if (!"".equals(row.get("result_json"))) {
                return row;
            }
            if (System.currentTimeMillis() >= deadlineMillis) {
                throw new ConflictException("requestId=" + rid + " 并发请求等待首次结果超时");
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("并发幂等等待被中断");
            }
        }
    }

    public String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    /** 反序列化带泛型的记录类型（如 RecomputeResult 含 List）。 */
    public <T> T fromJson(String json, JavaType type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    public JavaType listType(Class<?> element) {
        return objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, element);
    }

    private String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
