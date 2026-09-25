package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.Certificate;
import com.example.starter.domain.CertificateState;
import com.example.starter.domain.SeedGrade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 证书与认证留痕仓储：每批次唯一证书；注销时同步作废并留痕。
 */
@Repository
public class CertificateRepository {

    private static final CertificateRowMapper CERT_MAPPER = new CertificateRowMapper();

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入证书；依赖 uq_certificate_lot 唯一约束保证同批次并发发证仅一方成功。
     *
     * @return 受影响行数（1 成功，0 表示唯一约束冲突由上层转义）
     */
    public int insert(Certificate certificate) {
        return jdbc.update("""
                INSERT INTO certificate
                  (certificate_no, lot_no, grade, deducted_labels, status, issued_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """,
                certificate.certificateNo(), certificate.lotNo(), certificate.grade().name(),
                certificate.deductedLabels(), certificate.state().name(),
                Timestamp.from(certificate.issuedAt()));
    }

    /** 按批次编号查询证书。 */
    public Optional<Certificate> findByLotNo(String lotNo) {
        return jdbc.query("SELECT * FROM certificate WHERE lot_no = ?", CERT_MAPPER, lotNo)
                .stream().findFirst();
    }

    /** 按证书号查询证书。 */
    public Optional<Certificate> findByCertificateNo(String certificateNo) {
        return jdbc.query("SELECT * FROM certificate WHERE certificate_no = ?",
                CERT_MAPPER, certificateNo).stream().findFirst();
    }

    /** 将批次证书作废（注销终态时调用）。 */
    public int revokeByLotNo(String lotNo, Instant now) {
        return jdbc.update("""
                UPDATE certificate
                   SET status = 'REVOKED', revoked_at = ?
                 WHERE lot_no = ? AND status = 'VALID'
                """, Timestamp.from(now), lotNo);
    }

    /** 追加认证留痕。 */
    public void insertAudit(String lotNo, String action, String detail, String operator, Instant now) {
        jdbc.update("""
                INSERT INTO certification_audit (lot_no, action, detail, operator, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, lotNo, action, detail, operator, Timestamp.from(now));
    }

    /** 查询批次全部留痕，按时间升序。 */
    public List<AuditEntry> findAudits(String lotNo) {
        return jdbc.query("""
                SELECT lot_no, action, detail, operator, created_at
                  FROM certification_audit
                 WHERE lot_no = ?
                 ORDER BY id
                """, (rs, rowNum) -> new AuditEntry(
                rs.getString("lot_no"),
                rs.getString("action"),
                rs.getString("detail"),
                rs.getString("operator"),
                rs.getTimestamp("created_at").toInstant()), lotNo);
    }

    /** 认证留痕条目。 */
    public record AuditEntry(String lotNo, String action, String detail,
                             String operator, Instant createdAt) {
    }

    private static final class CertificateRowMapper implements RowMapper<Certificate> {
        @Override
        public Certificate mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp revokedAt = rs.getTimestamp("revoked_at");
            return new Certificate(
                    rs.getLong("id"),
                    rs.getString("certificate_no"),
                    rs.getString("lot_no"),
                    SeedGrade.valueOf(rs.getString("grade")),
                    rs.getInt("deducted_labels"),
                    CertificateState.valueOf(rs.getString("status")),
                    rs.getTimestamp("issued_at").toInstant(),
                    revokedAt == null ? null : revokedAt.toInstant());
        }
    }
}
