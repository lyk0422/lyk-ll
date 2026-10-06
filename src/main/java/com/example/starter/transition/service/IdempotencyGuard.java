package com.example.starter.transition.service;

import com.example.starter.transition.ApiException;
import com.example.starter.transition.dto.IdempotentResult;
import com.example.starter.transition.model.IdempotentRequest;
import com.example.starter.transition.repo.IdempotentRequestRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 幂等受理组件：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409；并发占位由唯一索引仲裁。
 */
@Component
public class IdempotencyGuard {

    private final IdempotentRequestRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyGuard(IdempotentRequestRepository repository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等记录查询：同键同参返回已完成的首次记录（调用方重放）；同键异参抛 409。
     * 读到未完成占位（首次事务尚未提交结果）时等待重试；查无记录则尝试插入占位，
     * 占位插入成功表示当前请求获得受理权，唯一索引冲突时重新查询。
     */
    public IdempotentRequest lookup(String requestId, String bizType, String fingerprint) {
        for (int i = 0; i < 25; i++) {
            Optional<IdempotentRequest> found = repository.find(requestId);
            if (found.isPresent()) {
                IdempotentRequest record = found.get();
                if (!bizType.equals(record.getBizType()) || !fingerprint.equals(record.getFingerprint())) {
                    throw ApiException.conflict("requestId=" + requestId + " 已用于不同业务参数，冲突拒绝");
                }
                if (record.getResultJson() != null) {
                    return record;
                }
                // 首次事务仍在进行，等待其提交后重放。
                sleepBriefly();
                continue;
            }
            try {
                IdempotentRequest placeholder = new IdempotentRequest();
                placeholder.setRequestId(requestId);
                placeholder.setBizType(bizType);
                placeholder.setFingerprint(fingerprint);
                placeholder.setResultRef(null);
                placeholder.setResultJson(null);
                repository.insert(placeholder, LocalDateTime.now(clock));
                return null;
            } catch (DuplicateKeyException e) {
                // 并发请求已插入占位（可能未提交），等待后重新查询。
                sleepBriefly();
            }
        }
        throw ApiException.conflict("并发幂等请求处理中，请稍后重放: " + requestId);
    }

    /** 业务完成后回写首次结果，供同键重放原样返回。 */
    public void register(String requestId, String resultRef, Object resultView) {
        repository.updateResult(requestId, resultRef, writeJson(resultView));
    }

    /** 重放首次结果。 */
    public <T> IdempotentResult<T> replay(IdempotentRequest record, TypeReference<T> type) {
        T data;
        try {
            byte[] bytes = record.getResultJson() == null
                    ? new byte[0] : record.getResultJson().getBytes(StandardCharsets.UTF_8);
            data = objectMapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等首次结果反序列化失败", e);
        }
        return new IdempotentResult<>(record.getRequestId(), true, data);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("并发幂等请求处理中断");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
