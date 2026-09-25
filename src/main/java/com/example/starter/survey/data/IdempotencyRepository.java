package com.example.starter.survey.data;

import com.example.starter.survey.domain.IdempotentRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 幂等记录数据访问。
 * requestId 为唯一键：并发首插只有一个成功（{@link DuplicateKeyException}），唯一受理。
 */
@Repository
public class IdempotencyRepository {

    /** 占位状态：已占键但业务结果尚未回填。 */
    public static final int STATUS_PENDING = 0;

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<IdempotentRecord> MAPPER = (rs, n) -> new IdempotentRecord(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getInt("http_status"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant()
    );

    /** 按 requestId 查询历史记录。 */
    public Optional<IdempotentRecord> find(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_record WHERE request_id = ?",
                        MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 抢占新 requestId（先占位，业务成功后由 {@link #complete} 回填结果）。
     *
     * @return true 表示抢占成功（首次）；false 表示键已存在（并发落败或重放）
     */
    public boolean reserve(String requestId, String fingerprint, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO idempotent_record
                                (request_id, fingerprint, http_status, response_body, created_at)
                            VALUES (?, ?, ?, '', ?)
                            """,
                    requestId, fingerprint, STATUS_PENDING, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 业务执行成功后回填首次响应结果。 */
    public void complete(String requestId, int httpStatus, String responseBody) {
        jdbc.update("UPDATE idempotent_record SET http_status = ?, response_body = ? WHERE request_id = ?",
                httpStatus, responseBody, requestId);
    }
}
