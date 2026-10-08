package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.store.IdempotencyRepository;
import com.example.starter.web.ApiException;

/**
 * 幂等执行框架：同事务内先落幂等记录再执行业务，并发同键由主键串行化；
 * 后到者捕获唯一键冲突后重放首次结果，同键异参返回 409。
 * 业务失败整体回滚，幂等记录不占键。
 *
 * @param <T> 响应类型
 */
@Component
public class IdempotentExecutor {

    private final IdempotencyRepository idem;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotentExecutor(IdempotencyRepository idem, TransactionTemplate tx,
                              ObjectMapper objectMapper, Clock clock) {
        this.idem = idem;
        this.tx = tx;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键异参 409，失败不占键。
     *
     * @param operation   写操作类型
     * @param requestId   幂等请求号
     * @param fingerprint 覆盖 requestId 与全部业务字段的结构化指纹
     * @param action      业务动作（与幂等记录同事务）
     * @param type        响应类型（重放反序列化用）
     */
    public <T> T execute(String operation, String requestId, String fingerprint,
                         Supplier<T> action, Class<T> type) {
        try {
            return tx.execute(status -> {
                var existing = idem.find(requestId);
                if (existing.isPresent()) {
                    return replay(existing.get(), fingerprint, type);
                }
                idem.insert(requestId, operation, fingerprint, "", LocalDateTime.now(clock));
                T response = action.get();
                idem.updateResponse(requestId, toJson(response));
                return response;
            });
        } catch (DuplicateKeyException e) {
            var existing = idem.find(requestId);
            if (existing.isPresent()) {
                return replay(existing.get(), fingerprint, type);
            }
            throw ApiException.conflict("幂等冲突，请重试");
        }
    }

    /** 重放首次结果；指纹不一致（同键异参）返回 409。 */
    private <T> T replay(IdempotencyRepository.IdemRow existing, String fingerprint, Class<T> type) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已存在且业务参数不一致");
        }
        try {
            return objectMapper.readValue(existing.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }
}
