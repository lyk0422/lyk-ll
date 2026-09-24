package com.example.starter.portability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

import com.example.starter.portability.dto.ErrorResponse;
import com.example.starter.portability.dto.StoredResponse;
import com.example.starter.portability.model.IdempotencyRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等执行器：以 requestId 为唯一键，指纹覆盖 requestId 与全部业务字段。
 * 同键同参重放首次结果；同键异参返回 409 且不改变流程状态；
 * 并发下同号唯一在途由数据库唯一约束兜底，冲突请求返回 409。
 */
@Component
public class IdempotencyExecutor {

    private final PortabilityRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyExecutor(PortabilityRepository repository,
                               TransactionTemplate transactionTemplate,
                               ObjectMapper objectMapper,
                               Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 业务动作结果：响应报文与关联流程标识（无流程场景为 null）。
     */
    public record Outcome(StoredResponse response, Long flowId) {
    }

    /**
     * 在事务内执行业务动作并写入幂等记录；已存在记录时按指纹重放或拒绝。
     */
    public StoredResponse execute(String requestId, String endpoint, String fingerprint,
                                  Supplier<Outcome> action) {
        var existing = repository.findIdempotency(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        try {
            return transactionTemplate.execute(status -> {
                Outcome outcome = action.get();
                repository.insertIdempotency(requestId, endpoint, fingerprint,
                        outcome.response().httpStatus(), outcome.response().body(),
                        outcome.flowId(), Instant.now(clock));
                return outcome.response();
            });
        } catch (DuplicateKeyException e) {
            // 并发场景：可能是同一 requestId 的重复提交，也可能是同号在途唯一键冲突
            return repository.findIdempotency(requestId)
                    .map(record -> replay(record, fingerprint))
                    .orElseGet(() -> errorResponse(409, "FLOW_IN_FLIGHT",
                            "同一号码已有在途流程，申请被拒绝"));
        }
    }

    /**
     * 计算请求指纹：requestId 与全部业务字段拼接后的 SHA-256。
     */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private StoredResponse replay(IdempotencyRecord record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            return errorResponse(409, "IDEMPOTENCY_CONFLICT",
                    "相同 requestId 已用于不同参数的请求，流程状态未改变");
        }
        return new StoredResponse(record.httpStatus(), record.responseBody());
    }

    private StoredResponse errorResponse(int httpStatus, String code, String message) {
        try {
            return new StoredResponse(httpStatus,
                    objectMapper.writeValueAsString(new ErrorResponse(code, message)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("错误响应序列化失败", e);
        }
    }
}
