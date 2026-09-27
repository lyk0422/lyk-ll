package com.example.starter.service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.starter.support.AppClock;
import com.example.starter.web.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 发电车域幂等存取：requestId 唯一键，首次成功时同事务写入结构化指纹与冻结结果JSON；
 * 同键同参重放首次结果，同键异参抛409，业务失败回滚不占键。
 */
@Component
public class GenIdempotency {

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final ObjectMapper objectMapper;

    public GenIdempotency(JdbcTemplate jdbc, AppClock clock, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 按 requestId 查询首次冻结结果；不存在返回空，指纹不一致抛409。
     */
    public <T> Optional<T> findReplay(String requestId, String fingerprint, Class<T> type) {
        List<String[]> rows = jdbc.query(
                "SELECT fingerprint, outcome FROM gen_idempotency WHERE request_id = ?",
                (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2)}, requestId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String[] row = rows.get(0);
        if (!row[0].equals(fingerprint)) {
            throw new ConflictException("同键异参幂等冲突: requestId=" + requestId);
        }
        try {
            return Optional.of(objectMapper.readValue(row[1], type));
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败: requestId=" + requestId, e);
        }
    }

    /**
     * 同事务记录首次冻结结果。
     */
    public void record(String requestId, String fingerprint, String op, long entityId, Object outcome) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(outcome);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败: requestId=" + requestId, e);
        }
        jdbc.update("INSERT INTO gen_idempotency (request_id, fingerprint, op, entity_id, outcome, created_at)"
                + " VALUES (?,?,?,?,?,?)",
                requestId, fingerprint, op, entityId, json, Timestamp.valueOf(clock.now()));
    }
}
