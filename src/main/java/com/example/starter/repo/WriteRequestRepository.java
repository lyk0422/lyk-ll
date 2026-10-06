package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 写操作幂等登记表数据访问。
 */
@Repository
public class WriteRequestRepository {

    /** 幂等登记记录。 */
    public record WriteRequest(long id, String requestId, String fingerprint, String resultJson, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public WriteRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按幂等键查询登记记录。 */
    public Optional<WriteRequest> findByRequestId(String requestId) {
        List<WriteRequest> rows = jdbc.query(
                "SELECT id, request_id, fingerprint, result_json, created_at FROM write_request WHERE request_id = ?",
                this::map, requestId);
        return rows.stream().findFirst();
    }

    /** 登记首次执行结果；幂等键冲突时抛出 DuplicateKeyException 由上层处理。 */
    public void insert(String requestId, String fingerprint, String resultJson, Instant createdAt) {
        jdbc.update("INSERT INTO write_request (request_id, fingerprint, result_json, created_at) VALUES (?, ?, ?, ?)",
                requestId, fingerprint, resultJson, Timestamp.from(createdAt));
    }

    /** 统计登记记录数（测试核验用）。 */
    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM write_request", Integer.class);
        return count == null ? 0 : count;
    }

    private WriteRequest map(ResultSet rs, int rowNum) throws SQLException {
        return new WriteRequest(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint") != null ? rs.getString("fingerprint") : null,
                rs.getString("result_json"),
                rs.getTimestamp("created_at").toInstant());
    }
}
