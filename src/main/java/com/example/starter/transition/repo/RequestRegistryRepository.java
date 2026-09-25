package com.example.starter.transition.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 幂等登记表数据访问：request_id 唯一，指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class RequestRegistryRepository {

    private final JdbcTemplate jdbc;

    public RequestRegistryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 尝试登记 PROCESSING；唯一约束保证并发同键唯一受理。 */
    public boolean tryRegister(String requestId, String bizType, String fingerprint) {
        try {
            jdbc.update("INSERT INTO request_registry (request_id, biz_type, fingerprint, status) "
                    + "VALUES (?, ?, ?, 'PROCESSING')", requestId, bizType, fingerprint);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<RegistryRow> findByRequestId(String requestId) {
        List<RegistryRow> list = jdbc.query(
                "SELECT request_id, biz_type, fingerprint, ref_key, response_json, status "
                        + "FROM request_registry WHERE request_id = ?",
                (rs, rowNum) -> new RegistryRow(
                        rs.getString("request_id"),
                        rs.getString("biz_type"),
                        rs.getString("fingerprint"),
                        rs.getString("ref_key"),
                        rs.getString("response_json"),
                        rs.getString("status")),
                requestId);
        return list.stream().findFirst();
    }

    public void markDone(String requestId, String refKey, String responseJson) {
        jdbc.update("UPDATE request_registry SET status = 'DONE', ref_key = ?, response_json = ?, "
                + "finished_at = CURRENT_TIMESTAMP WHERE request_id = ?",
                refKey, responseJson, requestId);
    }

    /** 业务处理失败时清理 PROCESSING 登记，允许客户端修正后重试。 */
    public void deleteByRequestId(String requestId) {
        jdbc.update("DELETE FROM request_registry WHERE request_id = ?", requestId);
    }

    /** 登记行快照。 */
    public record RegistryRow(String requestId, String bizType, String fingerprint,
                              String refKey, String responseJson, String status) {
    }
}
