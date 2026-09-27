package com.example.rebate.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 冲回写操作 requestId 幂等记录数据访问。
 *
 * <p>占坑插入在业务事务内完成：与业务变更同提交、同回滚——业务失败时占坑行随之回滚（不占键）；
 * 并发同键在主键上阻塞，先提交者胜出，后来者读到已提交的首次结果进行重放。</p>
 */
@Repository
public class ChargebackRequestRepository {

    private final JdbcTemplate jdbc;

    public ChargebackRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占坑。
     *
     * @return {@code true} 首次占位成功；{@code false} requestId 已存在（已提交的首次结果）
     */
    public boolean tryAcquire(String requestId, String opType, String fingerprint, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO t_chargeback_request
                        (request_id, op_type, fingerprint, response_status, response_body, created_at)
                    VALUES (?, ?, ?, NULL, NULL, ?)
                    """,
                    requestId, opType, fingerprint, Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 同事务回写首次成功结果（随业务事务一起提交）。 */
    public void complete(String requestId, int status, String body) {
        jdbc.update("""
                UPDATE t_chargeback_request
                SET response_status = ?, response_body = ?
                WHERE request_id = ?
                """,
                status, body, requestId);
    }

    /** 读取已提交的首次记录（指纹、状态、响应体）。 */
    public Map<String, Object> find(String requestId) {
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT request_id, op_type, fingerprint, response_status, response_body, created_at "
                        + "FROM t_chargeback_request WHERE request_id = ?",
                requestId);
        return list.stream().findFirst().orElse(null);
    }
}
