package com.example.rebate.repo;

import com.example.rebate.domain.IdempotentRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * requestId 幂等记录数据访问。
 *
 * <p>以 request_id 为主键先插入占坑行，保证同键并发请求只有一个进入实际业务；
 * 指纹不同返回 409，指纹相同返回首次结果。</p>
 */
@Repository
public class IdempotentRecordRepository {

    private static final RowMapper<IdempotentRecord> MAPPER = new RecordRowMapper();

    private final JdbcTemplate jdbc;

    public IdempotentRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占坑。
     *
     * @return {@code true} 首次占位成功，调用方执行业务；{@code false} requestId 已存在
     */
    public boolean tryAcquire(String requestId, String opType, String fingerprint, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO t_idempotent_record
                        (request_id, op_type, fingerprint, response_status, response_body, created_at)
                    VALUES (?, ?, ?, NULL, NULL, ?)
                    """,
                    requestId, opType, fingerprint, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 回写首次执行结果。
     */
    public void complete(String requestId, int status, String body, Instant now) {
        jdbc.update("""
                UPDATE t_idempotent_record
                SET response_status = ?, response_body = ?, completed_at = ?
                WHERE request_id = ?
                """,
                status, body, Timestamp.from(now), requestId);
    }

    /**
     * 查询幂等记录。
     */
    public List<IdempotentRecord> find(String requestId) {
        return jdbc.query(
                "SELECT request_id, op_type, fingerprint, response_status, response_body, "
                        + "created_at, completed_at FROM t_idempotent_record WHERE request_id = ?",
                MAPPER, requestId);
    }

    private static final class RecordRowMapper implements RowMapper<IdempotentRecord> {
        @Override
        public IdempotentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            Object status = rs.getObject("response_status");
            return new IdempotentRecord(
                    rs.getString("request_id"),
                    rs.getString("op_type"),
                    rs.getString("fingerprint"),
                    status == null ? null : ((Number) status).intValue(),
                    rs.getString("response_body"),
                    rs.getTimestamp("created_at").toInstant(),
                    toInstant(rs.getTimestamp("completed_at")));
        }

        private static Instant toInstant(Timestamp ts) {
            return ts == null ? null : ts.toInstant();
        }
    }
}
