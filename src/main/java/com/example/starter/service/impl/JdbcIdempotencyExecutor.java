package com.example.starter.service.impl;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.support.ApiException;
import com.example.starter.support.Fingerprints;
import com.example.starter.service.IdempotencyExecutor;
import com.example.starter.service.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于 {@code idempotent_request} 唯一主键的幂等执行器。
 *
 * <p>首次执行：业务动作与台账插入在同一事务，提交后生效；并发同键的落败方整事务回滚并重放获胜结果。
 * 业务失败：业务事务回滚（不改变工序状态），失败结果另起事务登记，同键同参重放仍返回首次失败结果。
 * 同键异参：不执行动作、台账不变，直接 409。
 */
@Component
public class JdbcIdempotencyExecutor implements IdempotencyExecutor {

    private static final String SELECT_SQL =
            "SELECT operation, fingerprint, status_code, response_type, response_json "
                    + "FROM idempotent_request WHERE request_id = ?";
    private static final String INSERT_SQL =
            "INSERT INTO idempotent_request (request_id, operation, fingerprint, status_code, "
                    + "response_type, response_json) VALUES (?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public JdbcIdempotencyExecutor(JdbcTemplate jdbcTemplate,
                                   PlatformTransactionManager transactionManager,
                                   ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Override
    public Outcome execute(String requestId, String operation, Object businessFields, Supplier<Outcome> action) {
        String fingerprint = fingerprint(requestId, operation, businessFields);
        try {
            return transactionTemplate.execute(status -> doInTransaction(requestId, operation, fingerprint, action));
        } catch (LostIdempotencyRaceException lostRace) {
            // 并发同键落败：本事务已回滚，转读获胜方首次结果。
            return resolveExisting(requireEntry(requestId), fingerprint);
        } catch (ApiException businessFailure) {
            // 业务动作被拒绝：业务事务已回滚，另起事务登记首次失败结果，失败也幂等。
            return recordFailure(requestId, operation, fingerprint, businessFailure);
        }
    }

    private Outcome doInTransaction(String requestId, String operation, String fingerprint,
                                    Supplier<Outcome> action) {
        LedgerEntry existing = findEntry(requestId);
        if (existing != null) {
            return resolveExisting(existing, fingerprint);
        }
        Outcome outcome;
        try {
            outcome = action.get();
        } catch (DuplicateKeyException businessUniqueRace) {
            // 业务唯一约束冲突（如并发同键重复提交撞 attempt/守卫约束）：本事务回滚，按落败处理。
            throw new LostIdempotencyRaceException();
        }
        try {
            insertEntry(requestId, operation, fingerprint, outcome);
        } catch (DuplicateKeyException lostRace) {
            // 并发同键：唯一约束冲突，本事务必将回滚，抛出后由外层在新事务中读取获胜结果。
            throw new LostIdempotencyRaceException();
        }
        return outcome;
    }

    private Outcome resolveExisting(LedgerEntry existing, String fingerprint) {
        if (!existing.fingerprint().equals(fingerprint)) {
            // 同键异参：409，动作未执行、台账不变。
            return Outcome.of(409, Map.of(
                    "errorCode", "IDEMPOTENCY_KEY_CONFLICT",
                    "message", "requestId 已用于不同的业务参数"));
        }
        return Outcome.replay(existing.statusCode(), parseJson(existing.responseType(), existing.responseJson()));
    }

    private Outcome recordFailure(String requestId, String operation, String fingerprint, ApiException failure) {
        Outcome failed = Outcome.of(failure.getStatus(),
                Map.of("errorCode", failure.getErrorCode(), "message", failure.getMessage()));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (findEntry(requestId) != null) {
                    // 并发场景获胜方已抢先登记，不在本事务插入以免唯一冲突。
                    throw new LostIdempotencyRaceException();
                }
                insertEntry(requestId, operation, fingerprint, failed);
            });
            return failed;
        } catch (LostIdempotencyRaceException | DuplicateKeyException winnerCommittedFirst) {
            return resolveExisting(requireEntry(requestId), fingerprint);
        }
    }

    private String fingerprint(String requestId, String operation, Object businessFields) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(businessFields);
        } catch (Exception e) {
            throw new IllegalStateException("业务字段序列化失败", e);
        }
        return Fingerprints.sha256(requestId, operation, json);
    }

    private void insertEntry(String requestId, String operation, String fingerprint, Outcome outcome) {
        String responseJson;
        try {
            responseJson = objectMapper.writeValueAsString(outcome.body());
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
        jdbcTemplate.update(INSERT_SQL, requestId, operation, fingerprint, outcome.status(),
                outcome.body().getClass().getName(), responseJson);
    }

    private LedgerEntry findEntry(String requestId) {
        var rows = jdbcTemplate.query(SELECT_SQL, (rs, rowNum) -> new LedgerEntry(
                rs.getString("operation"),
                rs.getString("fingerprint"),
                rs.getInt("status_code"),
                rs.getString("response_type"),
                rs.getString("response_json")), requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LedgerEntry requireEntry(String requestId) {
        // 并发落败方回滚后，获胜方可能尚未提交，短轮询等待其台账行可见。
        for (int attempt = 0; attempt < 50; attempt++) {
            LedgerEntry entry = findEntry(requestId);
            if (entry != null) {
                return entry;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("幂等台账缺失: " + requestId);
    }

    private Object parseJson(String typeName, String json) {
        try {
            Class<?> type = Class.forName(typeName);
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("台账响应反序列化失败: " + typeName, e);
        }
    }

    /** 台账行。 */
    private record LedgerEntry(String operation, String fingerprint, int statusCode,
                               String responseType, String responseJson) {
    }

    /** 并发同键落败标记：触发事务回滚后转读获胜结果。 */
    private static class LostIdempotencyRaceException extends ApiException {
        LostIdempotencyRaceException() {
            super(409, "IDEMPOTENCY_RACE_LOST", "并发同键请求落败");
        }
    }
}
