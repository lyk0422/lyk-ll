package com.example.starter.feed.repo;

import com.example.starter.feed.dto.SlaughterRevocationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 已批准出栏申请台账与撤销记录仓储。
 * 治疗直报重算后与新最早出栏日冲突的申请在同一事务内置为 REVOKED 并写撤销记录。
 */
@Repository
public class SlaughterApplicationRepository {

    private final JdbcTemplate jdbc;

    public SlaughterApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** slaughter_application 行。 */
    public record ApplicationRow(
            long id,
            String requestId,
            long batchId,
            String batchNo,
            Instant applyAtUtc,
            String status
    ) {
    }

    private static final RowMapper<ApplicationRow> APPLICATION_MAPPER = (rs, n) -> new ApplicationRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("batch_id"),
            rs.getString("batch_no"),
            DictRepository.toInstant(rs.getTimestamp("apply_at_utc")),
            rs.getString("status")
    );

    private static final RowMapper<SlaughterRevocationResponse> REVOCATION_MAPPER = (rs, n) ->
            new SlaughterRevocationResponse(
                    rs.getString("request_id"),
                    rs.getString("batch_no"),
                    DictRepository.toInstant(rs.getTimestamp("revoked_slaughter_at_utc")),
                    DictRepository.toInstant(rs.getTimestamp("new_earliest_slaughter_utc")),
                    rs.getLong("treatment_report_id"),
                    rs.getString("reason"),
                    DictRepository.toInstant(rs.getTimestamp("created_at_utc"))
            );

    /**
     * 登记一条已批准的出栏申请（台账）。
     */
    public void insertApproved(String requestId, long batchId, String batchNo, Instant applyAtUtc) {
        jdbc.update("INSERT INTO slaughter_application (request_id, batch_id, batch_no, apply_at_utc, status) "
                        + "VALUES (?,?,?,?,'APPROVED')",
                requestId, batchId, batchNo, Timestamp.from(applyAtUtc));
    }

    /**
     * 取批次当前仍处于 APPROVED 状态的出栏申请（供治疗直报冲突判定）。
     */
    public List<ApplicationRow> findApprovedByBatch(long batchId) {
        return jdbc.query("SELECT * FROM slaughter_application WHERE batch_id = ? AND status = 'APPROVED' "
                + "ORDER BY id", APPLICATION_MAPPER, batchId);
    }

    /**
     * 将申请置为 REVOKED（撤销台账状态，保留可查）。
     */
    public void markRevoked(long applicationId) {
        jdbc.update("UPDATE slaughter_application SET status = 'REVOKED' WHERE id = ?", applicationId);
    }

    /**
     * 写入撤销审计记录。
     */
    public void insertRevocation(long applicationId, String requestId, long batchId, String batchNo,
                                 Instant revokedSlaughterAtUtc, Instant newEarliestSlaughterUtc,
                                 long treatmentReportId, String reason) {
        jdbc.update("INSERT INTO slaughter_revocation (application_id, request_id, batch_id, batch_no, "
                        + "revoked_slaughter_at_utc, new_earliest_slaughter_utc, treatment_report_id, reason) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                applicationId, requestId, batchId, batchNo,
                Timestamp.from(revokedSlaughterAtUtc), Timestamp.from(newEarliestSlaughterUtc),
                treatmentReportId, reason);
    }

    /**
     * 查询批次全部撤销记录（按时间升序）。
     */
    public List<SlaughterRevocationResponse> findRevocationsByBatchNo(String batchNo) {
        return jdbc.query("SELECT * FROM slaughter_revocation WHERE batch_no = ? ORDER BY id",
                REVOCATION_MAPPER, batchNo);
    }
}
