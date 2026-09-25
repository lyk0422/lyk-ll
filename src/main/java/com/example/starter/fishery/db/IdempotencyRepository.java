package com.example.starter.fishery.db;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 幂等请求台账访问；主键冲突时区分同参重放与同键异参（409）。
 */
@Repository
public class IdempotencyRepository {

    /** 登记结果。 */
    public enum Outcome {
        /** 首次受理，调用方执行业务。 */
        ACQUIRED,
        /** 同键同参重放，返回首次结果。 */
        REPLAY,
        /** 同键请求尚在处理中。 */
        IN_PROGRESS,
        /** 同键异参（或异操作），冲突。 */
        MISMATCH
    }

    /** 登记结果及首次响应快照。 */
    public record AcquireResult(Outcome outcome, String responseJson) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 以 requestId 占座；指纹覆盖 requestId 之外的全部业务字段。
     * 依赖主键唯一约束：并发同键请求在数据库层串行化。
     */
    public AcquireResult acquire(String requestId, String operation, String fingerprint, Instant now) {
        try {
            jdbc.update("INSERT INTO idempotency_record(request_id, operation, fingerprint, response_json, "
                    + "created_at) VALUES (?, ?, ?, NULL, ?)",
                    requestId, operation, fingerprint, Timestamp.from(now));
            return new AcquireResult(Outcome.ACQUIRED, null);
        } catch (DuplicateKeyException ex) {
            IdempotencyRow existing = find(requestId);
            if (existing == null || !existing.operation().equals(operation)
                    || !existing.fingerprint().equals(fingerprint)) {
                return new AcquireResult(Outcome.MISMATCH, null);
            }
            if (existing.responseJson() == null) {
                return new AcquireResult(Outcome.IN_PROGRESS, null);
            }
            return new AcquireResult(Outcome.REPLAY, existing.responseJson());
        }
    }

    /** 业务成功后保存首次响应快照（同事务提交）。 */
    public void saveResponse(String requestId, String responseJson) {
        jdbc.update("UPDATE idempotency_record SET response_json = ? WHERE request_id = ?",
                responseJson, requestId);
    }

    private IdempotencyRow find(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, operation, fingerprint, response_json, created_at "
                        + "FROM idempotency_record WHERE request_id = ?",
                (rs, n) -> new IdempotencyRow(
                        rs.getString("request_id"),
                        rs.getString("operation"),
                        rs.getString("fingerprint"),
                        rs.getString("response_json"),
                        rs.getTimestamp("created_at").toInstant()),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
