package com.example.starter.service;

import com.example.starter.repository.IdempotentRepository;
import com.example.starter.repository.IdempotentRepository.IdempotentRecord;
import com.example.starter.web.ApiException;
import com.example.starter.web.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * requestId 幂等执行器。
 *
 * <p>指纹覆盖 requestId 与全部业务字段（请求体规范化 JSON）：
 * <ul>
 *   <li>同键同参：返回首次结果（成功响应或首次业务错误状态/错误码原样重放）；</li>
 *   <li>同键异参：409，且不执行业务、不改变调度状态；</li>
 *   <li>同键并发：先到请求以 PROCESSING 占位行串行化，后到请求等待首请求落定后重放。</li>
 * </ul>
 *
 * <p>占位与定稿由 {@link IdempotentRecordWriter} 以独立事务提交：业务事务即使回滚，
 * 首次业务失败（404/409/422）的结论快照仍然保留；非预期系统错误则清除占位允许重试。
 */
@Service
public class IdempotencyService {

    /** 占位响应体：首个请求仍在执行。 */
    static final String PROCESSING = "PROCESSING";

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);
    private static final Duration WAIT_STEP = Duration.ofMillis(20);

    private final IdempotentRepository repository;
    private final IdempotentRecordWriter writer;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotentRepository repository,
                              IdempotentRecordWriter writer,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.writer = writer;
        // 复制全局映射器配置，保证指纹序列化与响应快照使用同一规则。
        this.objectMapper = objectMapper.copy();
    }

    /**
     * 在调用方事务内执行幂等业务。
     *
     * @param requestId   幂等键
     * @param requestType 请求类型
     * @param request     业务请求对象（参与指纹的全部字段）
     * @param action      首次执行的业务动作，返回响应 DTO
     * @return 首次结果或重放结果
     */
    @Transactional
    public Outcome execute(String requestId, String requestType, Object request,
                           IdempotentAction action) {
        String fingerprint = fingerprint(requestType, request);

        IdempotentRecord existing = repository.find(requestId).orElse(null);
        if (existing != null) {
            return resolveExisting(existing, fingerprint);
        }

        // 首次：独立事务占位（携带指纹），使同键并发与异参请求在此串行。
        if (!writer.insertProcessing(requestId, requestType, fingerprint)) {
            return resolveExisting(repository.find(requestId).orElseThrow(), fingerprint);
        }

        Object response;
        try {
            response = action.run();
        } catch (ApiException api) {
            // 业务失败结论同样定稿并独立提交，随后抛出由统一异常处理响应；外层业务事务回滚。
            writer.finalizeRecord(requestId, api.getStatus(),
                    errorSnapshot(api.getCode(), api.getMessage()));
            throw api;
        } catch (RuntimeException unexpected) {
            // 非预期系统错误：清除占位，允许同键后续重试。
            writer.discardProcessing(requestId);
            throw unexpected;
        }
        writer.finalizeRecord(requestId, 200, writeJson(response));
        return new Outcome(false, 200, response, null);
    }

    private Outcome resolveExisting(IdempotentRecord record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENT_PARAM_MISMATCH",
                    "requestId 已用于不同业务参数的请求，拒绝执行");
        }
        String body = record.responseBody();
        if (PROCESSING.equals(body)) {
            return resolveExisting(awaitFinalized(record.requestId()), fingerprint);
        }
        if (record.httpStatus() == 200) {
            return new Outcome(true, 200, parseSnapshot(body), null);
        }
        // 重放首次业务失败：以相同状态码与错误码再次抛出，错误体由统一异常处理按当前请求生成。
        JsonNode node = parseJson(body);
        throw new ApiException(record.httpStatus(),
                node.path("code").asText(), node.path("message").asText());
    }

    private IdempotentRecord awaitFinalized(String requestId) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        do {
            try {
                Thread.sleep(WAIT_STEP.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("IDEMPOTENT_WAIT_INTERRUPTED", "等待首次请求完成时被中断");
            }
            IdempotentRecord record = repository.find(requestId).orElse(null);
            if (record == null) {
                throw new ConflictException("IDEMPOTENT_FIRST_ATTEMPT_FAILED",
                        "首次请求因系统错误已回滚，请稍后重试");
            }
            if (!PROCESSING.equals(record.responseBody())) {
                return record;
            }
        } while (System.nanoTime() < deadline);
        throw new ConflictException("IDEMPOTENT_IN_PROGRESS", "首次请求仍在处理中，请稍后重试");
    }

    /** 计算 requestType + 请求体规范化 JSON 的 SHA-256 指纹。 */
    public String fingerprint(String requestType, Object request) {
        String canonical = requestType + "|" + writeJson(request);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String errorSnapshot(String code, String message) {
        return writeJson(new ErrorSnapshot(code, message));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等快照解析失败", e);
        }
    }

    private Object parseSnapshot(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    /** 首次业务失败的快照载荷。 */
    private record ErrorSnapshot(String code, String message) {
    }

    /** 幂等动作：首次请求执行的业务逻辑。 */
    @FunctionalInterface
    public interface IdempotentAction {
        Object run();
    }

    /**
     * 幂等执行结果。
     *
     * @param replayed true 为重放首次结果
     * @param status   HTTP 状态码
     * @param body     首次执行为业务 DTO；重放成功时为已解析的快照对象
     * @param error   始终为 null（重放失败时直接抛出 ApiException，不产出 Outcome）
     */
    public record Outcome(boolean replayed, int status, Object body, Object error) {
    }
}
