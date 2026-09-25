package com.example.starter.survey.data;

import com.example.starter.survey.domain.ExtensionRequest;
import com.example.starter.survey.domain.ExtensionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** 年度检验展期申请数据访问；每个检验至多一条（uq_ext_per_survey）。 */
@Repository
public class ExtensionRepository {

    private final JdbcTemplate jdbc;

    public ExtensionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ExtensionRequest> MAPPER = (rs, n) -> new ExtensionRequest(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("survey_id"),
            rs.getObject("certificate_id") == null ? null : rs.getLong("certificate_id"),
            rs.getInt("months"),
            ExtensionStatus.valueOf(rs.getString("status")),
            rs.getObject("old_valid_until") == null ? null : rs.getDate("old_valid_until").toLocalDate(),
            rs.getObject("new_valid_until") == null ? null : rs.getDate("new_valid_until").toLocalDate(),
            rs.getTimestamp("applied_at").toInstant(),
            rs.getObject("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
            rs.getString("note"),
            rs.getString("fingerprint")
    );

    /** 按主键查找。 */
    public Optional<ExtensionRequest> findById(long id) {
        return jdbc.query("SELECT * FROM extension_request WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按检验查找（一检验至多一条展期）。 */
    public Optional<ExtensionRequest> findBySurvey(long surveyId) {
        return jdbc.query("SELECT * FROM extension_request WHERE survey_id = ?", MAPPER, surveyId)
                .stream().findFirst();
    }

    /** 登记展期申请（PENDING）。 */
    public ExtensionRequest insert(String requestId, long surveyId, Long certificateId, int months,
                                   LocalDate oldValidUntil, String fingerprint, Instant now) {
        jdbc.update("""
                        INSERT INTO extension_request (request_id, survey_id, certificate_id, months,
                                                       status, old_valid_until, new_valid_until,
                                                       applied_at, decided_at, note, fingerprint)
                        VALUES (?, ?, ?, ?, 'PENDING', ?, NULL, ?, NULL, NULL, ?)
                        """,
                requestId, surveyId, certificateId, months,
                oldValidUntil == null ? null : Date.valueOf(oldValidUntil),
                Timestamp.from(now), fingerprint);
        return jdbc.query("SELECT * FROM extension_request WHERE request_id = ?",
                        MAPPER, requestId)
                .stream().findFirst().orElseThrow();
    }

    /**
     * 批准：状态 APPROVED、写入新有效期；必须与证书 valid_until 更新同事务执行。
     */
    public void markApproved(long extensionId, Long certificateId,
                             LocalDate newValidUntil, Instant decidedAt) {
        int updated = jdbc.update("""
                        UPDATE extension_request
                        SET status = 'APPROVED', certificate_id = ?,
                            new_valid_until = ?, decided_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """,
                certificateId, Date.valueOf(newValidUntil), Timestamp.from(decidedAt), extensionId);
        if (updated != 1) {
            throw new IllegalStateException("展期批准更新行数异常: " + updated);
        }
    }

    /** 驳回。 */
    public void markRejected(long extensionId, String note, Instant decidedAt) {
        int updated = jdbc.update("""
                        UPDATE extension_request
                        SET status = 'REJECTED', note = ?, decided_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """, note, Timestamp.from(decidedAt), extensionId);
        if (updated != 1) {
            throw new IllegalStateException("展期驳回更新行数异常: " + updated);
        }
    }
}
