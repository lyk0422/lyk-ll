package com.example.starter.rights.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 写请求幂等台账：同一 requestId 仅允许同指纹重复，异指纹冲突。
 * 占位行（response_json 为 NULL）在业务事务内写入，业务回滚时随之释放。
 */
@Repository
public class RequestLedgerRepository {

    private final JdbcTemplate jdbc;

    public RequestLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LedgerEntry> find(String requestId) {
        return jdbc.query("SELECT request_type, fingerprint, status_code, response_json FROM request_ledger WHERE request_id = ?",
                (rs, n) -> new LedgerEntry(
                        rs.getString("request_type"),
                        rs.getString("fingerprint"),
                        rs.getInt("status_code"),
                        rs.getString("response_json")),
                requestId).stream().findFirst();
    }

    /** 事务内先占位：唯一索引保证同 requestId 的并发写只有一个能进入业务执行。 */
    public void insertPending(String requestId, String requestType, String fingerprint, Instant createdAt) {
        jdbc.update("""
                INSERT INTO request_ledger (request_id, request_type, fingerprint, status_code, response_json, created_at)
                VALUES (?,?,?,?,NULL,?)
                """, requestId, requestType, fingerprint, 0, Timestamp.from(createdAt));
    }

    /** 业务成功后在同一事务内写回首次响应快照。 */
    public void complete(String requestId, int statusCode, String responseJson) {
        jdbc.update("UPDATE request_ledger SET status_code = ?, response_json = ? WHERE request_id = ?",
                statusCode, responseJson, requestId);
    }

    public record LedgerEntry(String requestType, String fingerprint, int statusCode, String responseJson) {

        public boolean completed() {
            return responseJson != null;
        }
    }
}
