package com.example.starter.specimen.service;

import com.example.starter.specimen.error.ApiException;
import com.example.starter.specimen.model.IdempotencyRecordRow;
import com.example.starter.specimen.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等执行器：同键同参返回首次结果快照，同键异参返回 409；
 * 并发下同键由 request_id 主键唯一约束兜底，落败方回滚后重放首次结果。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Optional<IdempotencyRecordRow> find(String requestId) {
        return repository.findByRequestId(requestId);
    }

    /**
     * 重放已有记录：指纹一致返回快照反序列化结果，不一致抛出 409。
     */
    public <T> T replay(IdempotencyRecordRow record, String fingerprint, Class<T> type) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId " + record.requestId() + " 已存在，但请求参数不一致");
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照反序列化失败", e);
        }
    }

    /**
     * 在事务内执行幂等操作：先查重，再执行业务并保存快照；
     * 业务冲突时若已有同键记录则重放，并发唯一键冲突时回滚本事务后重放。
     */
    public <T> T execute(String requestId, String operation, String fingerprint,
                         Class<T> type, Supplier<T> business) {
        Optional<IdempotencyRecordRow> existing = repository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        try {
            T result = business.get();
            String snapshot;
            try {
                snapshot = objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                throw new IllegalStateException("幂等快照序列化失败", e);
            }
            repository.insert(requestId, operation, fingerprint, snapshot, Instant.now(clock));
            return result;
        } catch (DuplicateKeyException e) {
            // 并发同键：本事务作废，重放先提交者的结果
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            IdempotencyRecordRow winner = repository.findByRequestId(requestId)
                    .orElseThrow(() -> e);
            return replay(winner, fingerprint, type);
        } catch (ApiException e) {
            // 业务冲突（如版本已变化）可能源于首个同键请求已提交，优先重放
            if (e.status() == HttpStatus.CONFLICT) {
                Optional<IdempotencyRecordRow> record = repository.findByRequestId(requestId);
                if (record.isPresent()) {
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    return replay(record.get(), fingerprint, type);
                }
            }
            throw e;
        }
    }
}
