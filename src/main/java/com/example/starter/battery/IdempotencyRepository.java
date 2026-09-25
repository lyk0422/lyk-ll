package com.example.starter.battery;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求记录数据访问。
 */
@Repository
public class IdempotencyRepository {

    /** 幂等记录。 */
    public record IdempotencyRecord(String requestId, String operation, String fingerprint,
                                    String response, long createdAt) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试插入幂等记录。
     *
     * @return true 表示首次插入成功；false 表示 requestId 已存在
     */
    public boolean tryInsert(String requestId, String operation, String fingerprint,
                             String response, long now) {
        try {
            jdbc.update(
                    "INSERT INTO idempotency_record (request_id, operation, fingerprint, response, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    requestId, operation, fingerprint, response, now);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 业务处理完成后回填首次结果快照。 */
    public void updateResponse(String requestId, String response) {
        jdbc.update("UPDATE idempotency_record SET response = ? WHERE request_id = ?",
                response, requestId);
    }

    /** 业务处理失败时删除占位记录，允许客户端修正后重试。 */
    public void deleteByRequestId(String requestId) {
        jdbc.update("DELETE FROM idempotency_record WHERE request_id = ?", requestId);
    }

    public Optional<IdempotencyRecord> findByRequestId(String requestId) {
        return jdbc.query(
                        "SELECT * FROM idempotency_record WHERE request_id = ?",
                        (rs, rowNum) -> new IdempotencyRecord(
                                rs.getString("request_id"),
                                rs.getString("operation"),
                                rs.getString("fingerprint"),
                                rs.getString("response"),
                                rs.getLong("created_at")),
                        requestId)
                .stream().findFirst();
    }
}
