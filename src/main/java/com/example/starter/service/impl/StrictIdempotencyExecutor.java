package com.example.starter.service.impl;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.service.Outcome;
import com.example.starter.support.ApiException;
import com.example.starter.support.Fingerprints;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 采集协议专用幂等执行器：成功才占键，失败不占键。
 *
 * <p>与 {@link JdbcIdempotencyExecutor} 的差异：业务动作抛出的 {@link ApiException} 直接向外传播，
 * 不在幂等台账登记失败结果，因此同键可在修正参数后重试；只有成功结果（含响应体）与业务写入
 * 同事务落台账。同键同参重放首次冻结结果，同键异参 409。指纹为 requestId、操作类型与
 * 业务字段规范化 JSON 的 SHA-256，结构化无歧义（合法文本含分隔符也可区分）。
 */
@Component
public class StrictIdempotencyExecutor {

    private static final String SELECT_SQL =
            "SELECT operation, fingerprint, status_code, response_type, response_json "
                    + "FROM idempotent_request WHERE request_id = ?";
    private static final String INSERT_SQL =
            "INSERT INTO idempotent_request (request_id, operation, fingerprint, status_code, "
                    + "response_type, response_json) VALUES (?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public StrictIdempotencyExecutor(JdbcTemplate jdbcTemplate,
                                     PlatformTransactionManager transactionManager,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 在幂等保护下执行业务动作；成功结果与业务写入同事务落台账，失败不占键。
     *
     * @param requestId      幂等请求号
     * @param operation      操作类型
     * @param businessFields 全部业务字段（含版本、集合与精确时刻，参与指纹）
     * @param action         首次执行的业务动作
     * @return 首次结果或同键同参的重放结果
     * @throws ApiException 业务拒绝（不登记台账，同键可修正参数后重试）
     */
    public Outcome execute(String requestId, String operation, Object businessFields,
                           Supplier<Outcome> action) {
        String fingerprint = fingerprint(requestId, operation, businessFields);
        try {
            return transactionTemplate.execute(status -> doInTransaction(requestId, operation,
                    fingerprint, action));
        } catch (LostIdempotencyRaceException lostRace) {
            // 并发同键落败：本事务已回滚，转读获胜方首次结果。
            return resolveExisting(requireEntry(requestId), fingerprint);
        } catch (BusinessUniqueRaceException businessRace) {
            // 与异键并发写入撞业务唯一约束：本事务已回滚；若本键已有台账（同键竞态）则重放，
            // 否则按并发冲突 409 返回，失败不占键。
            LedgerEntry existing = findEntry(requestId);
            if (existing != null) {
                return resolveExisting(existing, fingerprint);
            }
            return Outcome.of(409, Map.of(
                    "errorCode", "CONCURRENT_MODIFICATION",
                    "message", "并发写入冲突，请按最新版本重试"));
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
            // 业务唯一约束冲突（并发撞包序号等）：本事务回滚，按业务竞态处理。
            throw new BusinessUniqueRaceException();
        }
        try {
            insertEntry(requestId, operation, fingerprint, outcome);
        } catch (DuplicateKeyException lostRace) {
            // 并发同键：唯一约束冲突，本事务必将回滚，抛出后由外层读取获胜结果。
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

    /** 业务唯一约束竞态标记：区别于台账同键竞态，落败后按并发冲突处理。 */
    private static class BusinessUniqueRaceException extends ApiException {
        BusinessUniqueRaceException() {
            super(409, "BUSINESS_UNIQUE_RACE", "并发业务唯一约束冲突");
        }
    }
}
