package com.example.starter.survey.repo;

import com.example.starter.survey.domain.CertificateRow;
import com.example.starter.survey.model.SurveyEnums.CertificateStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

/**
 * 证书版本链数据访问。新证签发与旧证失效必须在同一事务内完成。
 */
@Repository
public class CertificateRepo {

    private final JdbcTemplate jdbc;

    public CertificateRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CertificateRow findValidCertificate(long shipId, String typeCode) {
        List<CertificateRow> rows = jdbc.query(
                "SELECT * FROM certificate WHERE ship_id = ? AND type_code = ? AND valid_flag = 1",
                this::mapCert, shipId, typeCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public CertificateRow findById(long id) {
        List<CertificateRow> rows = jdbc.query(
                "SELECT * FROM certificate WHERE id = ?", this::mapCert, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<CertificateRow> findByShipType(long shipId, String typeCode) {
        return jdbc.query(
                "SELECT * FROM certificate WHERE ship_id = ? AND type_code = ? ORDER BY version",
                this::mapCert, shipId, typeCode);
    }

    public int nextVersion(long shipId, String typeCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM certificate WHERE ship_id = ? AND type_code = ?",
                Integer.class, shipId, typeCode);
        return (max == null ? 0 : max) + 1;
    }

    /** 旧证失效：valid_flag 置 NULL、状态 INVALID/EXPIRED。 */
    public int invalidate(CertificateRow cert, CertificateStatus status, LocalDate invalidatedAt) {
        return jdbc.update(
                "UPDATE certificate SET status = ?, valid_flag = NULL, invalidated_at = ? WHERE id = ?",
                status.name(), Date.valueOf(invalidatedAt), cert.id());
    }

    /** 插入新有效证书；与旧证失效同事务，唯一索引 uk_cert_valid 兜底。 */
    public long insertValid(String certNo, long shipId, String typeCode, long surveyId, int version,
                            LocalDate validFrom, LocalDate validUntil, LocalDate recheckDue,
                            LocalDate issuedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO certificate (cert_no, ship_id, type_code, survey_id, version, "
                                + "status, valid_from, valid_until, recheck_due, valid_flag, issued_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)",
                        new String[]{"id"});
                ps.setString(1, certNo);
                ps.setLong(2, shipId);
                ps.setString(3, typeCode);
                ps.setLong(4, surveyId);
                ps.setInt(5, version);
                ps.setString(6, CertificateStatus.VALID.name());
                ps.setDate(7, Date.valueOf(validFrom));
                ps.setDate(8, Date.valueOf(validUntil));
                ps.setDate(9, recheckDue == null ? null : Date.valueOf(recheckDue));
                ps.setDate(10, Date.valueOf(issuedAt));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            throw e;
        }
        return keyHolder.getKey().longValue();
    }

    /** 展期批准后同事务延长有效期（含 recheck_due 不变）。 */
    public int extendValidUntil(long certificateId, LocalDate newValidUntil) {
        return jdbc.update("UPDATE certificate SET valid_until = ? WHERE id = ?",
                Date.valueOf(newValidUntil), certificateId);
    }

    /** 复查逾期：证书失效。 */
    public int markExpired(long certificateId, LocalDate expiredAt) {
        return jdbc.update(
                "UPDATE certificate SET status = ?, valid_flag = NULL, invalidated_at = ? WHERE id = ?",
                CertificateStatus.EXPIRED.name(), Date.valueOf(expiredAt), certificateId);
    }

    private CertificateRow mapCert(ResultSet rs, int n) throws SQLException {
        Boolean valid = rs.getObject("valid_flag") == null ? null : rs.getInt("valid_flag") == 1;
        return new CertificateRow(
                rs.getLong("id"),
                rs.getString("cert_no"),
                rs.getLong("ship_id"),
                rs.getString("type_code"),
                rs.getLong("survey_id"),
                rs.getInt("version"),
                CertificateStatus.valueOf(rs.getString("status")),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_until", LocalDate.class),
                rs.getObject("recheck_due", LocalDate.class),
                valid,
                rs.getObject("issued_at", LocalDate.class),
                rs.getObject("invalidated_at", LocalDate.class));
    }
}
