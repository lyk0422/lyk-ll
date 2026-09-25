package com.example.starter.ventilation.repo;

import com.example.starter.ventilation.domain.IdemRequestRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * requestId 幂等记录仓储：唯一键保证同键请求只落一条。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdemRequestRow> MAPPER = (rs, n) -> new IdemRequestRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("request_type"),
            rs.getString("zone_code"),
            rs.getString("fingerprint"),
            rs.getString("status"),
            rs.getString("conflict_info"),
            rs.getString("result_json"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 requestId 查询首次记录，不存在返回 null。 */
    public IdemRequestRow find(String requestId) {
        var list = jdbc.query(
                "SELECT id, request_id, request_type, zone_code, fingerprint, status, "
                        + "conflict_info, result_json, created_at FROM idem_request WHERE request_id = ?",
                MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 插入首次请求记录。
     *
     * @return true 插入成功（获得首次处理权）；false 唯一键冲突（同键并发，已有记录）
     */
    public boolean insert(String requestId, String requestType, String zoneCode,
                          String fingerprint, String status, String conflictInfo,
                          String resultJson, long createdAt) {
        try {
            jdbc.update(
                    "INSERT INTO idem_request (request_id, request_type, zone_code, fingerprint, "
                            + "status, conflict_info, result_json, created_at) VALUES (?,?,?,?,?,?,?,?)",
                    requestId, requestType, zoneCode, fingerprint, status, conflictInfo,
                    resultJson, createdAt);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 首次处理完成后回填响应快照。 */
    public void updateResult(String requestId, String status, String conflictInfo, String resultJson) {
        jdbc.update(
                "UPDATE idem_request SET status = ?, conflict_info = ?, result_json = ? WHERE request_id = ?",
                status, conflictInfo, resultJson, requestId);
    }
}
