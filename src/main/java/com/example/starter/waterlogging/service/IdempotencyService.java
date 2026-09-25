package com.example.starter.waterlogging.service;

import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Map;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.waterlogging.exception.ApiException;
import com.example.starter.waterlogging.exception.BusinessRuleException;
import com.example.starter.waterlogging.exception.ConflictException;
import com.example.starter.waterlogging.exception.NotFoundException;
import com.example.starter.waterlogging.repo.IdempotentRequestRepository;
import com.example.starter.waterlogging.repo.IdempotentRequestRepository.StoredIdempotency;
import com.example.starter.waterlogging.web.IdempotentResponse;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * requestId 幂等编排：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果（成功或确定性业务失败均如此），同键异参返回 409 且不改变业务状态。
 *
 * <p>单个数据库事务内通过 JDBC 保存点实现“业务失败不留副作用、失败结果可重放”：
 * <ol>
 *   <li>先插入同键唯一的处理中占位记录抢占 requestId；</li>
 *   <li>设置保存点后在同事务/同连接内执行业务动作；</li>
 *   <li>成功：回填 200 与结果体并提交；</li>
 *   <li>确定性 4xx 业务失败：回滚到保存点撤销全部业务副作用，仅保留失败结果记录后提交；</li>
 *   <li>非确定性异常：整事务回滚（占位与业务副作用一并消失）。</li>
 * </ol>
 * 并发同键请求由唯一索引串行化，负方不执行业务动作，直接重放先到结果。
 */
@Service
public class IdempotencyService {

    private final IdempotentRequestRepository repository;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public IdempotencyService(IdempotentRequestRepository repository,
                              DataSource dataSource,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 在幂等保护下执行业务动作。
     *
     * @param requestId    幂等键
     * @param requestType  业务类型
     * @param canonicalMap 参与指纹的规范化字段（含 requestId，字典序）
     * @param resultType   首次成功结果类型，用于重放反序列化
     * @param action       首办业务动作（在保存点之后执行）
     */
    public <T> IdempotentResponse<T> run(String requestId, String requestType, Map<String, Object> canonicalMap,
                                         Class<T> resultType, Supplier<T> action) {
        String fingerprint = fingerprint(canonicalMap);

        StoredIdempotency existing = repository.find(requestId, requestType).orElse(null);
        if (existing != null) {
            return resolveExisting(existing, fingerprint, resultType);
        }

        Outcome<T> outcome = transactionTemplate.execute(status -> {
            // 事务内复查，覆盖拿到连接前并发方已提交的情况。
            StoredIdempotency recheck = repository.find(requestId, requestType).orElse(null);
            if (recheck != null) {
                return new Outcome.Recheck<>();
            }
            // 先占坑：唯一索引保证同键并发只有一方继续，负方不触碰业务状态。
            if (!repository.insertPlaceholder(requestId, requestType, fingerprint)) {
                return new Outcome.Recheck<>();
            }

            Savepoint savepoint = createSavepoint();
            try {
                T result = action.get();
                repository.completeOutcome(requestId, requestType, HttpStatus.OK.value(), toJson(result));
                return new Outcome.First<>(result);
            } catch (ApiException ex) {
                if (ex.getStatus().is4xxClientError()) {
                    // 撤销保存点之后的全部业务副作用，仅保留占位记录用于记录失败结果。
                    rollbackToSavepoint(savepoint);
                    StoredFailure failure = new StoredFailure(
                            ex.getStatus().value(), ex.getCode(), ex.getMessage());
                    repository.completeOutcome(requestId, requestType,
                            ex.getStatus().value(), toJson(failure));
                    return new Outcome.Failed<>(failure);
                }
                throw ex;
            }
        });

        if (outcome instanceof Outcome.First<T> first) {
            return IdempotentResponse.first(first.value());
        }
        if (outcome instanceof Outcome.Failed<T> failed) {
            // 首办即业务失败：向首次调用方抛出原错误，台账已留存供重放。
            throw failed.failure().toException();
        }
        // 并发负方：重放先到结果。
        StoredIdempotency winner = repository.find(requestId, requestType)
                .orElseThrow(() -> new ConflictException("IDEMPOTENCY_RACE", "并发请求受理结果未定，请重试"));
        return resolveExisting(winner, fingerprint, resultType);
    }

    private Savepoint createSavepoint() {
        try {
            return DataSourceUtils.getConnection(dataSource).setSavepoint("biz_action");
        } catch (SQLException e) {
            throw new IllegalStateException("创建业务动作保存点失败", e);
        }
    }

    private void rollbackToSavepoint(Savepoint savepoint) {
        try {
            DataSourceUtils.getConnection(dataSource).rollback(savepoint);
        } catch (SQLException e) {
            throw new IllegalStateException("回滚业务动作保存点失败", e);
        }
    }

    private <T> IdempotentResponse<T> resolveExisting(StoredIdempotency stored, String fingerprint,
                                                      Class<T> resultType) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "同一 requestId 已用于不同业务参数，拒绝执行且不改变泵站状态");
        }
        if (stored.responseStatus() == IdempotentRequestRepository.RESPONSE_PENDING) {
            throw new ConflictException("IDEMPOTENCY_IN_PROGRESS",
                    "同一 requestId 的首次请求仍在处理中，请稍后重试");
        }
        if (stored.responseStatus() != HttpStatus.OK.value()) {
            fromJson(stored.responseBody(), StoredFailure.class).throwAsOriginal();
        }
        return IdempotentResponse.replayed(fromJson(stored.responseBody(), resultType));
    }

    private String fingerprint(Map<String, Object> canonicalMap) {
        return FingerprintUtil.sha256(toJson(canonicalMap));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等重放反序列化失败", e);
        }
    }

    /** 首办事务结果：成功 / 确定性业务失败 / 需重放先到结果。 */
    private sealed interface Outcome<T> permits Outcome.First, Outcome.Failed, Outcome.Recheck {
        record First<T>(T value) implements Outcome<T> {
        }

        record Failed<T>(StoredFailure failure) implements Outcome<T> {
        }

        record Recheck<T>() implements Outcome<T> {
        }
    }

    /** 持久化的确定性业务失败，重放时还原为原状态码与错误码。 */
    public record StoredFailure(
            @JsonProperty("status") int status,
            @JsonProperty("code") String code,
            @JsonProperty("message") String message) {

        @JsonCreator
        public StoredFailure {
        }

        private ApiException toException() {
            HttpStatus httpStatus = HttpStatus.valueOf(status);
            return switch (httpStatus) {
                case CONFLICT -> new ConflictException(code, message);
                case NOT_FOUND -> new NotFoundException(code, message);
                case UNPROCESSABLE_ENTITY -> new BusinessRuleException(code, message);
                default -> new ApiException(httpStatus, code, message);
            };
        }

        private void throwAsOriginal() {
            throw toException();
        }
    }
}
