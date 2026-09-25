package com.example.starter.damage.service.support;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.repo.IdempotentRequestRepository;
import com.example.starter.damage.repo.po.IdempotentRequestPo;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * requestId 幂等门：
 * <ul>
 *   <li>同键同参：返回首次结果，不重复执行业务；</li>
 *   <li>同键异参：409 IDEMPOTENCY_CONFLICT，且不产生新版本等副作用；</li>
 *   <li>并发首请求：由主键唯一约束仲裁，负方等待胜方提交后重放其结果。</li>
 * </ul>
 * 调用方须处于事务内：占位行随业务事务提交/回滚。
 */
@Component
public class IdempotencyGate {

    private static final long WAIT_STEP_MILLIS = 50L;
    private static final long WAIT_TIMEOUT_MILLIS = 30_000L;

    private final IdempotentRequestRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyGate(IdempotentRequestRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T run(String operation, String requestId, String fingerprint, int httpStatus,
                     Class<T> responseType, java.util.function.Supplier<T> action) {
        IdempotentRequestPo existing = repository.findByRequestId(requestId).orElse(null);
        if (existing == null) {
            try {
                repository.insert(requestId, operation, fingerprint, null, httpStatus, "",
                        System.currentTimeMillis());
            } catch (DuplicateKeyException concurrentWinner) {
                existing = awaitCommitted(requestId);
            }
        }
        if (existing != null) {
            return replay(existing, requestId, fingerprint, responseType);
        }
        T result = action.get();
        repository.updateResponse(requestId, extractCaseNo(result), httpStatus, toJson(result));
        return result;
    }

    @SuppressWarnings("unchecked")
    private String extractCaseNo(Object result) {
        if (result instanceof com.example.starter.damage.api.dto.CaseViews.CaseResponse c) {
            return c.caseNo();
        }
        if (result instanceof com.example.starter.damage.api.dto.CaseViews.RegisterResponse r) {
            return r.caseNo();
        }
        return null;
    }

    private <T> T replay(IdempotentRequestPo existing, String requestId, String fingerprint,
                         Class<T> responseType) {
        if (!fingerprint.equals(existing.fingerprint())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId=" + requestId + " 已用于不同业务参数，拒绝重复请求");
        }
        if (existing.responseJson() == null || existing.responseJson().isEmpty()) {
            IdempotentRequestPo committed = awaitCommitted(requestId);
            if (committed == null) {
                throw ApiException.conflict("IDEMPOTENCY_IN_FLIGHT",
                        "requestId=" + requestId + " 正在处理中，请稍后重试");
            }
            existing = committed;
        }
        try {
            return objectMapper.readValue(existing.responseJson(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败: " + requestId, e);
        }
    }

    /** 等待并发胜方事务提交（行可见且响应已写入）。 */
    private IdempotentRequestPo awaitCommitted(String requestId) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<IdempotentRequestPo> po = repository.findByRequestId(requestId);
            if (po.isPresent() && po.get().responseJson() != null && !po.get().responseJson().isEmpty()) {
                return po.get();
            }
            try {
                Thread.sleep(WAIT_STEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待幂等首请求结果时被中断", e);
            }
        }
        return null;
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }
}
