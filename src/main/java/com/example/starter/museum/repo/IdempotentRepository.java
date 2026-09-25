package com.example.starter.museum.repo;

import com.example.starter.museum.domain.po.IdempotentRequestPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Optional;

/**
 * requestId 幂等记录仓储。
 * 主键即 requestId：首次执行业务前先占位，唯一约束拒绝并发重复请求。
 */
@Repository
public class IdempotentRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotentRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Occupy(boolean acquired, IdempotentRequestPo existing) {
    }

    /**
     * 占位：requestId 首次出现则插入（response_json 暂为 NULL）并返回 acquired=true；
     * 已存在则返回既有记录，由上层比对指纹。
     */
    public Occupy occupy(String requestId, String fingerprint) {
        try {
            jdbc.update(
                    "INSERT INTO idempotent_request (request_id, fingerprint, http_status, response_json, created_at) VALUES (?, ?, 0, NULL, ?)",
                    requestId, fingerprint, Sql.ts(clock.instant()));
            return new Occupy(true, null);
        } catch (DuplicateKeyException e) {
            IdempotentRequestPo po = get(requestId)
                    .orElseThrow(() -> new IllegalStateException("幂等记录在并发后消失: " + requestId));
            return new Occupy(false, po);
        }
    }

    public Optional<IdempotentRequestPo> get(String requestId) {
        return jdbc.query("SELECT * FROM idempotent_request WHERE request_id = ?",
                RowMappers.IDEMPOTENT, requestId).stream().findFirst();
    }

    public void saveResult(String requestId, int httpStatus, String responseJson) {
        jdbc.update(
                "UPDATE idempotent_request SET http_status = ?, response_json = ? WHERE request_id = ?",
                httpStatus, responseJson, requestId);
    }
}
