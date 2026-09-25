package com.example.starter.runway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.runway.RunwayClosureRepository.IdempotencyRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次响应快照，同键异参返回 409；
 * 并发下同键请求依赖 (operation, request_id) 唯一约束收敛到首次结果。
 */
@Component
public class IdempotencyExecutor {

    private final RunwayClosureRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyExecutor(RunwayClosureRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在调用方事务内执行幂等操作。
     *
     * @param operation      操作类型
     * @param requestId      幂等请求号
     * @param businessFields 全部业务字段的确定性拼接（不含 requestId）
     * @param action         首次执行的业务动作，返回将序列化为响应快照的对象
     * @return 响应 JSON（首次序列化结果或首次快照）
     */
    public String execute(String operation, String requestId, String businessFields, Supplier<Object> action) {
        String fingerprint = sha256(operation + '|' + requestId + '|' + businessFields);
        var existing = repository.findIdempotency(operation, requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        Object result = runAction(operation, requestId, fingerprint, action);
        String responseBody = toJson(result);
        try {
            repository.insertIdempotency(operation, requestId, fingerprint, responseBody, Instant.now(clock));
        } catch (DuplicateKeyException duplicate) {
            // 并发同键：另一事务已提交首次结果
            return repository.findIdempotency(operation, requestId)
                    .map(row -> replayOrConflict(row, fingerprint))
                    .orElseThrow(() -> duplicate);
        }
        return responseBody;
    }

    private Object runAction(String operation, String requestId, String fingerprint, Supplier<Object> action) {
        try {
            return action.get();
        } catch (RuntimeException ex) {
            // 并发同键请求可能在业务唯一约束上冲突；若首次结果已提交则按重放返回
            var committed = repository.findIdempotency(operation, requestId);
            if (committed.isPresent() && committed.get().fingerprint().equals(fingerprint)) {
                throw new ReplayException(committed.get().responseBody());
            }
            throw ex;
        }
    }

    private String replayOrConflict(IdempotencyRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("相同 requestId 的请求参数不一致");
        }
        return row.responseBody();
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 内部信号：并发同键请求命中已提交的首次结果，由服务层转换为正常响应。
     */
    public static final class ReplayException extends RuntimeException {

        private final String responseBody;

        ReplayException(String responseBody) {
            this.responseBody = responseBody;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }
}
