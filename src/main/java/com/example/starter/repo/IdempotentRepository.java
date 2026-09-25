package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

import com.example.starter.domain.IdempotentRecord;
import com.example.starter.domain.RequestKind;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * requestId 幂等记录仓储：主键即 requestId，同键并发插入仅一方成功。
 */
@Repository
public class IdempotentRepository {

    private static final IdempotentRowMapper ROW_MAPPER = new IdempotentRowMapper();

    private final JdbcTemplate jdbc;

    public IdempotentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 首次落库幂等记录。
     *
     * @return true 表示首次占用 requestId 成功；false 表示 requestId 已存在
     */
    public boolean tryInsert(IdempotentRecord record) {
        try {
            int rows = jdbc.update("""
                    INSERT INTO idempotent_request
                      (request_id, request_type, fingerprint, lot_no, http_status,
                       response_body, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.requestId(), record.kind().name(), record.fingerprint(),
                    record.lotNo(), record.httpStatus(), record.responseBody(),
                    Timestamp.from(record.createdAt()));
            return rows == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 查询已有幂等记录（同键重放/冲突判定）。 */
    public Optional<IdempotentRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ?",
                ROW_MAPPER, requestId).stream().findFirst();
    }

    /** 查询已有幂等记录并加行级写锁；并发同键请求据此等待首单提交后重放。 */
    public Optional<IdempotentRecord> findForUpdate(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ? FOR UPDATE",
                ROW_MAPPER, requestId).stream().findFirst();
    }

    /** 业务处理完成后回填首次响应，供后续同键重放原样返回。 */
    public void saveResponse(String requestId, int httpStatus, String responseBody) {
        jdbc.update("""
                UPDATE idempotent_request
                   SET http_status = ?, response_body = ?
                 WHERE request_id = ?
                """, httpStatus, responseBody, requestId);
    }

    private static final class IdempotentRowMapper implements RowMapper<IdempotentRecord> {
        @Override
        public IdempotentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdempotentRecord(
                    rs.getString("request_id"),
                    RequestKind.valueOf(rs.getString("request_type")),
                    rs.getString("fingerprint"),
                    rs.getString("lot_no"),
                    rs.getInt("http_status"),
                    rs.getString("response_body"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
