package com.example.starter.service;

import com.example.starter.repository.IdempotentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 幂等占位行与定稿行的独立事务写入器。
 *
 * <p>占位/定稿使用 REQUIRES_NEW 独立提交，使同键并发请求可立即观察到 PROCESSING；
 * 业务事务随后即使回滚（业务失败），失败快照仍保留，满足同键同参返回首次结果。
 */
@Component
public class IdempotentRecordWriter {

    private final IdempotentRepository repository;

    public IdempotentRecordWriter(IdempotentRepository repository) {
        this.repository = repository;
    }

    /** 独立事务插入 PROCESSING 占位行；同键并发冲突返回 false。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertProcessing(String requestId, String requestType, String fingerprint) {
        return repository.insertProcessing(requestId, requestType, fingerprint, Instant.now());
    }

    /** 独立事务将占位行定稿为首次结果（成功或业务失败）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeRecord(String requestId, int httpStatus, String responseBody) {
        repository.finalizeRecord(requestId, httpStatus, responseBody);
    }

    /** 独立事务删除占位行（非预期系统错误时允许同键后续重试）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discardProcessing(String requestId) {
        repository.deleteByRequestId(requestId);
    }
}
