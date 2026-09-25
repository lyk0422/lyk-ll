package com.example.starter.bonded.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 幂等记录数据访问：申请与核销首次成功时写入；同键同参回放首次响应，同键异参判 409。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等记录行。
     *
     * @param fingerprint 首次请求指纹
     * @param responseBody 首次成功响应报文（JSON）
     */
    public record Record(String fingerprint, String responseBody) {
    }

    /**
     * 插入幂等记录，重复键时返回 0（并发情况下由唯一约束兜底）。
     */
    public int insertIfAbsent(String requestId, String fingerprint, String responseBody, Instant now) {
        return jdbc.update(
                "INSERT INTO idempotency_record (request_id, fingerprint, response_body, created_at) VALUES (?,?,?,?)",
                requestId, fingerprint, responseBody, Timestamp.from(now));
    }

    /**
     * 事务内抢占插入幂等占位（响应体暂空）：并发同键的第二个事务会被唯一约束阻塞，
     * 待首个事务提交后收到唯一冲突，从而回放首次结果。业务失败回滚时占位一并回滚，键可重用。
     *
     * @return 1 抢占成功；0 键已存在
     */
    public int insertPlaceholder(String requestId, String fingerprint, Instant now) {
        return jdbc.update(
                "INSERT INTO idempotency_record (request_id, fingerprint, response_body, created_at) VALUES (?,?,?,?)",
                requestId, fingerprint, "", Timestamp.from(now));
    }

    /**
     * 业务成功后回写首次响应报文。
     */
    public int updateBody(String requestId, String responseBody) {
        return jdbc.update("UPDATE idempotency_record SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }

    /**
     * 查询已有幂等记录。
     */
    public Optional<Record> find(String requestId) {
        List<Record> list = jdbc.query(
                "SELECT fingerprint, response_body FROM idempotency_record WHERE request_id = ?",
                (rs, n) -> new Record(rs.getString(1), rs.getString(2)), requestId);
        return list.stream().findFirst();
    }
}
