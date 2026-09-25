package com.example.starter.blend;

import com.example.starter.blend.BlendDtos.ErrorBody;
import com.example.starter.blend.BlendRepository.IdemRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 幂等执行器：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参返回 409 且不执行业务、不占用罐量。
 * 幂等记录使用独立事务（REQUIRES_NEW）提交，业务异常结果同样落库，保证重放语义一致。
 */
@Component
public class IdempotencyExecutor {

    private static final int STATUS_PROCESSING = -1;

    private final BlendRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public IdempotencyExecutor(BlendRepository repository, ObjectMapper objectMapper, Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    /**
     * 在幂等保护下执行业务动作。
     *
     * @param endpoint    接口标识
     * @param requestId     幂等键
     * @param fingerprint 请求指纹（requestId + 全部业务字段）
     * @param bodyType    响应类型
     * @param action      业务动作
     * @return 首次执行结果或重放的首次结果
     */
    public <T> T execute(String endpoint, String requestId, String fingerprint,
                         Class<T> bodyType, Supplier<T> action) {
        if (!tryInsertPlaceholder(endpoint, requestId, fingerprint)) {
            return replay(requestId, fingerprint, bodyType);
        }
        try {
            T body = action.get();
            complete(requestId, 200, writeJson(body));
            return body;
        } catch (BlendException e) {
            complete(requestId, e.status(),
                    writeJson(new ErrorBody("BUSINESS_REJECTED", e.getMessage())));
            throw e;
        } catch (RuntimeException e) {
            // 系统异常不留幂等记录，允许调用方重试
            requiresNew.executeWithoutResult(s -> repository.deleteIdempotency(requestId));
            throw e;
        }
    }

    /**
     * 计算请求指纹：SHA-256 覆盖 requestId 与全部业务字段的规范串。
     */
    public String fingerprint(String requestId, Object... businessFields) {
        StringBuilder canonical = new StringBuilder(requestId == null ? "" : requestId);
        for (Object field : businessFields) {
            canonical.append('|').append(field == null ? "null" : field.toString());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private boolean tryInsertPlaceholder(String endpoint, String requestId, String fingerprint) {
        return Boolean.TRUE.equals(requiresNew.execute(status -> {
            try {
                repository.insertIdempotency(requestId, endpoint, fingerprint, LocalDateTime.now(clock));
                return true;
            } catch (DuplicateKeyException e) {
                return false;
            }
        }));
    }

    private <T> T replay(String requestId, String fingerprint, Class<T> bodyType) {
        IdemRecord record = repository.findIdempotency(requestId)
                .orElseThrow(() -> BlendException.conflict("幂等记录不存在，请重试"));
        if (!record.fingerprint().equals(fingerprint)) {
            throw BlendException.conflict("requestId已使用且业务参数不一致，拒绝执行");
        }
        if (record.responseStatus() == STATUS_PROCESSING) {
            throw BlendException.conflict("相同请求正在处理中，请稍后重试");
        }
        if (record.responseStatus() == 200) {
            try {
                return objectMapper.readValue(record.responseBody(), bodyType);
            } catch (Exception e) {
                throw new IllegalStateException("幂等响应反序列化失败", e);
            }
        }
        throw new BlendException(record.responseStatus(), extractMessage(record.responseBody()));
    }

    private void complete(String requestId, int status, String body) {
        requiresNew.executeWithoutResult(s -> repository.completeIdempotency(requestId, status, body));
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private String extractMessage(String body) {
        try {
            ErrorBody error = objectMapper.readValue(body, ErrorBody.class);
            return error.message();
        } catch (Exception e) {
            return "首次请求已被拒绝";
        }
    }
}
