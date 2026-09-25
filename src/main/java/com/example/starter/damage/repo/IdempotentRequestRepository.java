package com.example.starter.damage.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.damage.repo.po.IdempotentRequestPo;

/** requestId 幂等表数据访问。 */
@Repository
public class IdempotentRequestRepository {

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotentRequestPo> findByRequestId(String requestId) {
        List<IdempotentRequestPo> list = jdbc.query("""
                SELECT request_id, operation, fingerprint, case_no, http_status,
                       response_json, created_at_utc
                FROM idempotent_request WHERE request_id = ?
                """, (rs, n) -> new IdempotentRequestPo(
                rs.getString("request_id"),
                rs.getString("operation"),
                rs.getString("fingerprint"),
                rs.getString("case_no"),
                rs.getInt("http_status"),
                rs.getString("response_json"),
                (Long) rs.getObject("created_at_utc")), requestId);
        return list.stream().findFirst();
    }

    /** 插入幂等记录；重复键返回 0 行（并发首请求竞争）。 */
    public int insert(String requestId, String operation, String fingerprint, String caseNo,
                      int httpStatus, String responseJson, long nowUtc) {
        return jdbc.update("""
                INSERT INTO idempotent_request
                    (request_id, operation, fingerprint, case_no, http_status, response_json, created_at_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, requestId, operation, fingerprint, caseNo, httpStatus, responseJson, nowUtc);
    }

    public void updateResponse(String requestId, String caseNo, int httpStatus, String responseJson) {
        jdbc.update("UPDATE idempotent_request SET case_no = ?, http_status = ?, response_json = ? WHERE request_id = ?",
                caseNo, httpStatus, responseJson, requestId);
    }
}
