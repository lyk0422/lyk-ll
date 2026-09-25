package com.example.starter.cems.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.cems.repo.EventRepository;
import com.example.starter.cems.web.ConflictException;
import com.example.starter.cems.web.dto.MutationResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * requestId 幂等支撑：指纹覆盖 requestId 与全部业务字段；同键同参回放首次结果，同键异参 409。
 * 采用“先占用 requestId（主键）→ 执行业务 → 写回结果”的顺序：
 * 调用方处于同一事务，业务写入与幂等占用同提交，并发同键时后到者在业务执行前即被唯一索引拒绝，
 * 从而做到唯一受理、重放安全，且同键异参不改变任何排口状态。
 */
@Component
public class IdempotencySupport {

    /** 占用占位结果，表示首次请求仍在处理中。 */
    private static final String PENDING = "";

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EventRepository eventRepository;

    public IdempotencySupport(ObjectMapper objectMapper, Clock cemsClock,
                              EventRepository eventRepository) {
        this.objectMapper = objectMapper;
        this.clock = cemsClock;
        this.eventRepository = eventRepository;
    }

    /**
     * 幂等受理。
     *
     * @param requestId   幂等键
     * @param action      动作类型
     * @param outfallCode 关联排口
     * @param requestBody 原始请求（全部业务字段参与指纹）
     * @param resultType  首次结果类型（回放时反序列化）
     * @param firstTime   首次受理业务逻辑
     */
    public <T> MutationResponse<T> execute(String requestId, String action, String outfallCode,
                                           Object requestBody, Class<T> resultType,
                                           Supplier<T> firstTime) {
        String requestJson = canonicalJson(requestBody);
        String fingerprint = fingerprint(requestId, action, requestJson);

        EventRepository.IdempotentRow existing = eventRepository.findIdempotent(requestId);
        if (existing != null) {
            return resolveExisting(requestId, fingerprint, existing, resultType);
        }

        try {
            // 先占用 requestId；并发同键时此处唯一索引拒绝，后到者尚未执行任何业务写入
            eventRepository.insertIdempotent(requestId, action, outfallCode, fingerprint,
                    requestJson, PENDING, LocalDateTime.now(clock));
        } catch (DuplicateKeyException dup) {
            // 首个事务可能尚未提交（READ_COMMITTED 下暂不可见），有界等待其提交后按同键规则处理
            EventRepository.IdempotentRow raced = awaitCommitted(requestId);
            if (raced != null) {
                return resolveExisting(requestId, fingerprint, raced, resultType);
            }
            throw dup;
        }

        T result = firstTime.get();
        eventRepository.updateIdempotentResult(requestId, writeJson(result));
        return MutationResponse.first(result);
    }

    private <T> MutationResponse<T> resolveExisting(
            String requestId, String fingerprint,
            EventRepository.IdempotentRow existing, Class<T> resultType) {
        if (!existing.fingerprint().equals(fingerprint)) {
            // 同键异参：409 且不执行任何业务逻辑、不改变排口状态
            throw new ConflictException("requestId 已用于不同业务内容（同键异参）: " + requestId);
        }
        if (PENDING.equals(existing.resultJson())) {
            throw new ConflictException("相同 requestId 的首次请求仍在处理中: " + requestId);
        }
        return MutationResponse.replay(readResult(existing.resultJson(), resultType));
    }

    /**
     * 同键并发时首个事务可能刚好提交：有界等待幂等记录可见。
     * 返回 null 表示等待超时（此时保留原始唯一键冲突异常）。
     */
    private EventRepository.IdempotentRow awaitCommitted(String requestId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            EventRepository.IdempotentRow row = eventRepository.findIdempotent(requestId);
            if (row != null && !PENDING.equals(row.resultJson())) {
                return row;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return eventRepository.findIdempotent(requestId);
    }

    /** 序列化对象为字段名排序、数值去尾零的规范 JSON。 */
    String canonicalJson(Object body) {
        JsonNode node = objectMapper.convertValue(body, JsonNode.class);
        JsonNode normalized = normalize(node);
        return writeJson(normalized);
    }

    private JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            if (node != null && node.isNumber()) {
                BigDecimal decimal = node.decimalValue().stripTrailingZeros();
                BigDecimal normalized = decimal.scale() < 0 ? decimal.setScale(0) : decimal;
                return JsonNodeFactory.instance.numberNode(normalized);
            }
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(normalize(child)));
            return array;
        }
        // 对象：字段名按字典序排序，消除序列化顺序差异
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
            object.set(e.getKey(), normalize(e.getValue()));
        }
        return object;
    }

    /** SHA-256(requestId | action | 规范请求 JSON)，指纹覆盖 requestId 与全部业务字段。 */
    private String fingerprint(String requestId, String action, String requestJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(requestId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(action.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(requestJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("计算幂等指纹失败", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化幂等结果失败", ex);
        }
    }

    private <T> T readResult(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("回放幂等结果失败", ex);
        }
    }
}
