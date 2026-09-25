package com.example.starter.pv.data.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import com.example.starter.pv.data.SubmissionPo;
import com.example.starter.pv.data.SubmissionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * pv_submission 的 JDBC 实现。
 */
@Repository
public class JdbcSubmissionRepository implements SubmissionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSubmissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(SubmissionPo po) {
        jdbcTemplate.update("""
                INSERT INTO pv_submission
                    (request_id, request_kind, request_fingerprint, case_no, version_no,
                     merged_duplicate, response_json, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                po.requestId(), po.requestKind(), po.requestFingerprint(), po.caseNo(),
                po.versionNo(), po.mergedDuplicate(), po.responseJson(),
                Timestamp.from(po.createdAt()));
    }

    @Override
    public void complete(String requestId, String caseNo, int versionNo, boolean mergedDuplicate,
                         String responseJson) {
        int rows = jdbcTemplate.update("""
                UPDATE pv_submission
                SET case_no = ?, version_no = ?, merged_duplicate = ?, response_json = ?
                WHERE request_id = ?
                """,
                caseNo, versionNo, mergedDuplicate, responseJson, requestId);
        if (rows != 1) {
            throw new IllegalStateException("幂等记录回填失败: " + requestId);
        }
    }

    @Override
    public Optional<SubmissionPo> findByRequestId(String requestId) {
        List<SubmissionPo> list = jdbcTemplate.query(
                "SELECT id, request_id, request_kind, request_fingerprint, case_no, version_no,"
                        + " merged_duplicate, response_json, created_at"
                        + " FROM pv_submission WHERE request_id = ?",
                new SubmissionRowMapper(), requestId);
        return list.stream().findFirst();
    }

    /**
     * 幂等记录行映射器。
     */
    static class SubmissionRowMapper implements RowMapper<SubmissionPo> {
        @Override
        public SubmissionPo mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SubmissionPo(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("request_kind"),
                    rs.getString("request_fingerprint"),
                    rs.getString("case_no"),
                    (Integer) rs.getObject("version_no"),
                    rs.getBoolean("merged_duplicate"),
                    rs.getString("response_json"),
                    rs.getTimestamp("created_at").toInstant());
        }
    }
}
