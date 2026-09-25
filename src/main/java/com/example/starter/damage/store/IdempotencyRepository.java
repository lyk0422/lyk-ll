package com.example.starter.damage.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 幂等请求记录数据访问：request_id 为主键，占位插入实现并发唯一受理。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等记录行 */
    public record IdemRow(
            String requestId,
            String endpoint,
            String fingerprint,
            String status,
            String responseJson) {
    }

    /** 按 requestId 查询记录 */
    public Optional<IdemRow> find(String requestId) {
        return jdbc.query("select * from idempotency_record where request_id = ?",
                        (rs, n) -> new IdemRow(
                                rs.getString("request_id"),
                                rs.getString("endpoint"),
                                rs.getString("fingerprint"),
                                rs.getString("status"),
                                rs.getString("response_json")),
                        requestId)
                .stream().findFirst();
    }

    /** 插入处理中占位；同 requestId 冲突时抛出 DuplicateKeyException */
    public void insertInProgress(String requestId, String endpoint, String fingerprint, LocalDateTime now) {
        jdbc.update("insert into idempotency_record(request_id, endpoint, fingerprint, status, created_at)"
                        + " values (?,?,?,?,?)",
                requestId, endpoint, fingerprint, "IN_PROGRESS", now);
    }

    /** 标记完成并写入首次响应报文 */
    public void complete(String requestId, String responseJson) {
        jdbc.update("update idempotency_record set status = 'COMPLETED', response_json = ? where request_id = ?",
                responseJson, requestId);
    }

    /** 删除占位（业务失败时释放，允许重试） */
    public void delete(String requestId) {
        jdbc.update("delete from idempotency_record where request_id = ?", requestId);
    }
}
