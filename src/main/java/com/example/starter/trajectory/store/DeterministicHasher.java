package com.example.starter.trajectory.store;

import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.dto.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确定性 JSON 序列化与 SHA-256 哈希：
 * 载荷哈希不含 {@code requestId}（幂等键本身不参与同参判定），
 * 结果哈希不含 {@code resultHash} 字段自身；Map 按键排序、{-0.0} 归一化为 0，
 * 保证重复执行与并行执行得到相同摘要。
 */
@Component
public class DeterministicHasher {

    private final ObjectMapper mapper;

    public DeterministicHasher() {
        this.mapper = JsonMapperBuilderFactory.create();
    }

    /** 除 {@code requestId} 外全部请求参数的稳定哈希，用于同键异参冲突判定。 */
    public String payloadHash(ValidationRequest request) {
        return sha256(canonicalBytes(mapper.valueToTree(payloadMap(request))));
    }

    /** 规范化请求载荷 JSON（不含幂等键本身），用于审计持久化。 */
    public String toPayloadJsonUnchecked(ValidationRequest request) {
        try {
            synchronized (mapper) {
                return mapper.writeValueAsString(normalize(mapper.valueToTree(payloadMap(request))));
            }
        } catch (IOException e) {
            throw new IllegalStateException("请求序列化失败: " + request.requestId(), e);
        }
    }

    private Map<String, Object> payloadMap(ValidationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleVersion", request.ruleVersion());
        payload.put("initialState", request.initialState());
        payload.put("trajectory", request.trajectory());
        payload.put("obstacles", request.obstacles());
        payload.put("roadBoundary", request.roadBoundary());
        return payload;
    }

    /** 结果内容（不含哈希字段自身）的稳定哈希。 */
    public String resultHash(ValidationResult result) {
        return sha256(canonicalBytes(resultTree(result, false)));
    }

    /** 结果的规范化 JSON 文本，用于持久化，内容含 resultHash，可完整还原。 */
    public String toResultJson(ValidationResult result) throws IOException {
        synchronized (mapper) {
            return mapper.writeValueAsString(normalize(resultTree(result, true)));
        }
    }

    /** 仓库内部使用：序列化失败转为非受查异常。 */
    public String toResultJsonUnchecked(ValidationResult result) {
        try {
            return toResultJson(result);
        } catch (IOException e) {
            throw new IllegalStateException("结果序列化失败: " + result.requestId(), e);
        }
    }

    /** 从持久化 JSON 还原结果。 */
    public ValidationResult fromResultJson(String json) throws IOException {
        synchronized (mapper) {
            return mapper.readValue(json, ValidationResult.class);
        }
    }

    private ObjectNode resultTree(ValidationResult result, boolean includeHash) {
        ObjectNode node = mapper.createObjectNode();
        node.put("requestId", result.requestId());
        node.put("ruleVersion", result.ruleVersion());
        node.put("safe", result.safe());
        node.put("failureCount", result.failureCount());
        node.set("failuresByRule", mapper.valueToTree(result.failuresByRule()));
        node.set("failures", mapper.valueToTree(result.failures()));
        if (includeHash) {
            node.put("resultHash", result.resultHash());
        }
        return node;
    }

    private byte[] canonicalBytes(JsonNode tree) {
        try {
            synchronized (mapper) {
                return mapper.writeValueAsBytes(normalize(tree));
            }
        } catch (IOException e) {
            throw new IllegalStateException("确定性序列化失败", e);
        }
    }

    /**
     * 递归归一化：保持对象字段声明顺序、Map 键序由 mapper 保证，数值 -0.0 统一为 0，
     * 避免等价数值产生不同哈希。
     */
    private JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            node.fieldNames().forEachRemaining(name -> out.set(name, normalize(node.get(name))));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode child : node) {
                out.add(normalize(child));
            }
            return out;
        }
        if (node.isNumber() && Double.compare(node.doubleValue(), 0.0) == 0) {
            return mapper.getNodeFactory().numberNode(0);
        }
        return node;
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    /** ObjectMapper 工厂，单独抽出避免构造期 this 逸出。 */
    private static final class JsonMapperBuilderFactory {
        private static ObjectMapper create() {
            return new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        }
    }
}
