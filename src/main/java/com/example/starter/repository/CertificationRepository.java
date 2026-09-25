package com.example.starter.repository;

import com.example.starter.domain.CertStatus;
import com.example.starter.domain.Certificate;
import com.example.starter.domain.Grade;
import com.example.starter.domain.RecheckApplication;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 复检申请与证书数据访问。
 */
@Repository
public class CertificationRepository {

    private static final RowMapper<RecheckApplication> RECHECK_MAPPER = (rs, rowNum) ->
            new RecheckApplication(
                    rs.getLong("id"),
                    rs.getLong("lot_id"),
                    rs.getString("request_id"),
                    rs.getString("status"),
                    rs.getTimestamp("applied_at").toInstant(),
                    rs.getDate("deadline_at").toLocalDate(),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Certificate> CERT_MAPPER = (rs, rowNum) -> {
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        return new Certificate(
                rs.getLong("id"),
                rs.getString("cert_no"),
                rs.getLong("lot_id"),
                Grade.valueOf(rs.getString("grade")),
                rs.getInt("label_count"),
                CertStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("issued_at").toInstant(),
                voidedAt == null ? null : voidedAt.toInstant(),
                rs.getString("void_reason"),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public CertificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertRecheck(RecheckApplication application) {
        jdbcTemplate.update("""
                INSERT INTO recheck_application
                    (lot_id, request_id, status, applied_at, deadline_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                application.lotId(), application.requestId(), application.status(),
                Timestamp.from(application.appliedAt()), Date.valueOf(application.deadlineAt()),
                Timestamp.from(application.createdAt()));
    }

    public RecheckApplication findRecheckByLotId(long lotId) {
        List<RecheckApplication> list = jdbcTemplate.query(
                "SELECT * FROM recheck_application WHERE lot_id = ?", RECHECK_MAPPER, lotId);
        return list.isEmpty() ? null : list.get(0);
    }

    public RecheckApplication findRecheckByRequestId(String requestId) {
        List<RecheckApplication> list = jdbcTemplate.query(
                "SELECT * FROM recheck_application WHERE request_id = ?", RECHECK_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insertCertificate(Certificate certificate) {
        jdbcTemplate.update("""
                INSERT INTO certificate
                    (cert_no, lot_id, grade, label_count, status, issued_at,
                     voided_at, void_reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                certificate.certNo(), certificate.lotId(), certificate.grade().name(),
                certificate.labelCount(), certificate.status().name(),
                Timestamp.from(certificate.issuedAt()),
                certificate.voidedAt() == null ? null : Timestamp.from(certificate.voidedAt()),
                certificate.voidReason(), Timestamp.from(certificate.createdAt()));
    }

    /**
     * 条件发证插入：仅当批次仍为 QUALIFIED 且该批次尚无证书时插入成功。
     * 依赖 certificate.uk_certificate_lot 与本条件语句共同保证并发下只有一次发证成功。
     *
     * @return 插入行数（1 成功，0 表示批次状态已变化或已发证）
     */
    public int insertCertificateIfQualified(Certificate certificate) {
        return jdbcTemplate.update("""
                INSERT INTO certificate
                    (cert_no, lot_id, grade, label_count, status, issued_at,
                     voided_at, void_reason, created_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
                 FROM seed_lot sl
                WHERE sl.id = ?
                  AND sl.status = 'QUALIFIED'
                  AND NOT EXISTS (SELECT 1 FROM certificate c WHERE c.lot_id = sl.id)
                """,
                certificate.certNo(), certificate.lotId(), certificate.grade().name(),
                certificate.labelCount(), certificate.status().name(),
                Timestamp.from(certificate.issuedAt()),
                certificate.voidedAt() == null ? null : Timestamp.from(certificate.voidedAt()),
                certificate.voidReason(), Timestamp.from(certificate.createdAt()),
                certificate.lotId());
    }

    public Certificate findCertificateByLotId(long lotId) {
        List<Certificate> list = jdbcTemplate.query(
                "SELECT * FROM certificate WHERE lot_id = ?", CERT_MAPPER, lotId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Certificate findCertificateByCertNo(String certNo) {
        List<Certificate> list = jdbcTemplate.query(
                "SELECT * FROM certificate WHERE cert_no = ?", CERT_MAPPER, certNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 作废已发证书（注销时同事务调用并留痕）。 */
    public void voidCertificate(long lotId, Instant voidedAt, String reason) {
        jdbcTemplate.update("""
                UPDATE certificate
                   SET status = 'VOID',
                       voided_at = ?,
                       void_reason = ?
                 WHERE lot_id = ?
                   AND status = 'VALID'
                """,
                Timestamp.from(voidedAt), reason, lotId);
    }
}
