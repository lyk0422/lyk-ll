package com.example.starter.survey.repo;

import com.example.starter.survey.domain.IdempotentRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 幂等请求表数据访问。requestId 为主键；指纹覆盖操作类型与全部业务字段。
 * 采用“先占坑（http_status=0）→业务完成后定稿”两阶段，保证并发同键唯一执行。
 */
@Repository
public class IdempotentRepo {

    /** 占坑行尚未定稿。 */
    public static final int PENDING_STATUS = 0;

    private final JdbcTemplate jdbc;

    public IdempotentRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public IdempotentRow find(String requestId) {
        List<IdempotentRow> rows = jdbc.query(
                "SELECT * FROM idempotent_request WHERE request_id = ?", this::map, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 占坑：同键并发时唯一索引保证仅一个成功，其余抛 {@link DuplicateKeyException}。 */
    public void claim(String requestId, String operation, String fingerprint) {
        jdbc.update("INSERT INTO idempotent_request (request_id, operation, fingerprint, "
                        + "http_status, result_json, created_at) "
                        + "VALUES (?, ?, ?, ?, '', CURRENT_TIMESTAMP)",
                requestId, operation, fingerprint, PENDING_STATUS);
    }

    /** 业务完成后定稿首次结果（成功或业务失败快照）。 */
    public void finalizeResult(String requestId, int httpStatus, String resultJson) {
        jdbc.update("UPDATE idempotent_request SET http_status = ?, result_json = ? "
                + "WHERE request_id = ?", httpStatus, resultJson, requestId);
    }

    /** 系统异常时释放占坑，允许同键重试。 */
    public void release(String requestId) {
        jdbc.update("DELETE FROM idempotent_request WHERE request_id = ? AND http_status = ?",
                requestId, PENDING_STATUS);
    }

    private IdempotentRow map(ResultSet rs, int n) throws SQLException {
        return new IdempotentRow(
                rs.getString("request_id"),
                rs.getString("operation"),
                rs.getString("fingerprint"),
                rs.getInt("http_status"),
                rs.getString("result_json"),
                rs.getObject("created_at", java.time.LocalDateTime.class));
    }
}
