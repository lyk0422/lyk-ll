package com.example.starter.service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.starter.support.AppClock;
import com.example.starter.web.ConflictException;

/**
 * 发电车域幂等存储：requestId 唯一，指纹不一致抛 409；记录与业务写入同事务，失败回滚不占键。
 */
@Component
public class IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final AppClock clock;

    public IdempotencyStore(JdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 按 requestId 查询首次冻结结果JSON；同键异参抛 409，无记录返回空。
     */
    public Optional<String> findResult(String requestId, String fingerprint) {
        List<String[]> rows = jdbc.query(
                "SELECT fingerprint, result_json FROM generator_idempotency WHERE request_id = ?",
                (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2)}, requestId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String[] row = rows.get(0);
        if (!row[0].equals(fingerprint)) {
            throw new ConflictException("同键异参幂等冲突: requestId=" + requestId);
        }
        return Optional.of(row[1]);
    }

    /**
     * 保存首次冻结结果，与业务写入同事务提交。
     */
    public void save(String requestId, String fingerprint, String resultJson) {
        jdbc.update("INSERT INTO generator_idempotency (request_id, fingerprint, result_json, created_at)"
                + " VALUES (?,?,?,?)",
                requestId, fingerprint, resultJson, Timestamp.valueOf(clock.now()));
    }
}
