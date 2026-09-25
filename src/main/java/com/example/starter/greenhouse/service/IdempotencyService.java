package com.example.starter.greenhouse.service;

import com.example.starter.greenhouse.domain.IdempotentBizType;
import com.example.starter.greenhouse.po.IdempotentRequest;
import com.example.starter.greenhouse.repo.IdempotentRequestRepository;
import com.example.starter.greenhouse.repo.IdempotentUpdates;
import com.example.starter.greenhouse.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * requestId 幂等执行器：
 * 同键同参返回首次结果（含首次 HTTP 状态），同键异参返回 409。
 * 占位记录与业务变更在同一事务：业务失败时占位一并回滚。
 */
@Service
public class IdempotencyService {

    /** 业务动作结果：HTTP 状态 + 响应体对象（序列化为 JSON 存档，重放时按原类型还原）。 */
    public record ActionResult<T>(int httpStatus, T body) {
    }

    private final IdempotentRequestRepository repository;
    private final IdempotentUpdates updates;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotentRequestRepository repository,
                              IdempotentUpdates updates,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.repository = repository;
        this.updates = updates;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行。fingerprint 必须覆盖 requestId 与全部业务字段；
     * bodyType 用于同键重放时把存档 JSON 还原为原响应类型。
     */
    @Transactional
    public <T> ActionResult<T> execute(IdempotentBizType bizType, String requestId,
                                       String fingerprint, Supplier<ActionResult<T>> action,
                                       Class<T> bodyType) {
        Optional<IdempotentRequest> existing = repository.find(requestId);
        if (existing.isEmpty()) {
            existing = tryInsertPlaceholder(bizType, requestId, fingerprint);
        }
        if (existing.isPresent()) {
            IdempotentRequest record = existing.get();
            if (!record.getFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已用于不同业务参数，拒绝处理");
            }
            if (record.getResponseBody() != null) {
                return new ActionResult<>(record.getHttpStatus(), readBody(record.getResponseBody(), bodyType));
            }
            // 极端并发：另一事务已占位但尚未提交完成，等待后重放。
            throw ApiException.conflict("相同 requestId 的请求正在处理中，请稍后重放");
        }

        ActionResult<T> result = action.get();
        updates.fillResponse(requestId, result.httpStatus(), toJson(result.body()));
        return result;
    }

    private Optional<IdempotentRequest> tryInsertPlaceholder(IdempotentBizType bizType,
                                                             String requestId, String fingerprint) {
        IdempotentRequest placeholder = new IdempotentRequest();
        placeholder.setRequestId(requestId);
        placeholder.setBizType(bizType.name());
        placeholder.setFingerprint(fingerprint);
        placeholder.setHttpStatus(200);
        placeholder.setResponseBody(null);
        placeholder.setCreatedAt(Instant.now(clock));
        try {
            repository.insert(placeholder);
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            return awaitCommitted(requestId);
        }
    }

    private Optional<IdempotentRequest> awaitCommitted(String requestId) {
        for (int i = 0; i < 100; i++) {
            Optional<IdempotentRequest> found = repository.find(requestId);
            if (found.isPresent()) {
                return found;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("相同 requestId 的请求正在处理中，请稍后重放");
            }
        }
        throw ApiException.conflict("相同 requestId 的请求正在处理中，请稍后重放");
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应体序列化失败", e);
        }
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应体反序列化失败", e);
        }
    }
}
