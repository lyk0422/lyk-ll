package com.example.starter.survey.data;

import com.example.starter.survey.domain.Certificate;
import com.example.starter.survey.domain.CertificateStatus;
import com.example.starter.survey.domain.SurveyType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 证书版本数据访问。
 * valid_flag='Y' 配合唯一索引 uq_certificate_valid 保证：
 * 按“船＋检验类型”同一时刻至多一张有效证书；新证插入与旧证失效必须同事务完成。
 */
@Repository
public class CertificateRepository {

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Certificate> MAPPER = (rs, n) -> new Certificate(
            rs.getLong("id"),
            rs.getLong("ship_id"),
            SurveyType.valueOf(rs.getString("type")),
            rs.getInt("version"),
            CertificateStatus.valueOf(rs.getString("status")),
            rs.getDate("valid_from").toLocalDate(),
            rs.getObject("valid_until") == null ? null : rs.getDate("valid_until").toLocalDate(),
            rs.getObject("survey_id") == null ? null : rs.getLong("survey_id"),
            rs.getObject("superseded_by") == null ? null : rs.getLong("superseded_by"),
            rs.getTimestamp("created_at").toInstant()
    );

    /** 按主键查找。 */
    public Optional<Certificate> findById(long id) {
        return jdbc.query("SELECT * FROM certificate WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 查找当前有效证书（不加锁）。 */
    public Optional<Certificate> findValid(long shipId, SurveyType type) {
        return jdbc.query("""
                        SELECT * FROM certificate
                        WHERE ship_id = ? AND type = ? AND valid_flag = 'Y'
                        """, MAPPER, shipId, type.name())
                .stream().findFirst();
    }

    /**
     * 在事务内行锁当前有效证书（FOR UPDATE），用于发证原子交接，避免并发双有效。
     */
    public Optional<Certificate> findValidForUpdate(long shipId, SurveyType type) {
        return jdbc.query("""
                        SELECT * FROM certificate
                        WHERE ship_id = ? AND type = ? AND valid_flag = 'Y'
                        FOR UPDATE
                        """, MAPPER, shipId, type.name())
                .stream().findFirst();
    }

    /** 取该“船＋类型”的下一版本号（无历史版本时为 1）。 */
    public int nextVersion(long shipId, SurveyType type) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM certificate WHERE ship_id = ? AND type = ?",
                Integer.class, shipId, type.name());
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 插入新版本证书。
     * 以自增主键回取本事务插入的行：并发发证时对方可能已提交同 version 行，
     * 按 (ship_id, type, version) 回取会错取对方行，导致 markValid 作用错行、
     * 本事务挂起行残留提交。
     *
     * @param validFlag true 插入即有效（唯一索引槽位须已释放）；false 先挂起再由 {@link #markValid} 生效
     */
    public Certificate insert(long shipId, SurveyType type, int version,
                              CertificateStatus status, LocalDate validFrom, LocalDate validUntil,
                              Long surveyId, boolean validFlag, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO certificate (ship_id, type, version, status, valid_from, valid_until,
                                                     survey_id, superseded_by, created_at, valid_flag)
                            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, shipId);
            ps.setString(2, type.name());
            ps.setInt(3, version);
            ps.setString(4, status.name());
            ps.setObject(5, Date.valueOf(validFrom));
            ps.setObject(6, validUntil == null ? null : Date.valueOf(validUntil));
            ps.setObject(7, surveyId);
            ps.setTimestamp(8, Timestamp.from(now));
            ps.setString(9, validFlag ? "Y" : null);
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    /** 旧证失效：状态 SUPERSEDED、释放唯一索引槽位、记录替代版本。 */
    public void markSuperseded(long oldCertificateId, long newCertificateId) {
        int updated = jdbc.update("""
                        UPDATE certificate
                        SET status = 'SUPERSEDED', valid_flag = NULL, superseded_by = ?
                        WHERE id = ?
                        """, newCertificateId, oldCertificateId);
        if (updated != 1) {
            throw new IllegalStateException("旧证失效更新行数异常: " + updated);
        }
    }

    /** 挂起证书转为有效（原子交接最后一步）。 */
    public void markValid(long certificateId) {
        int updated = jdbc.update(
                "UPDATE certificate SET status = 'VALID', valid_flag = 'Y' WHERE id = ?",
                certificateId);
        if (updated != 1) {
            throw new IllegalStateException("新证生效更新行数异常: " + updated);
        }
    }

    /** 展期批准：与展期状态更新同事务修改有效期至。 */
    public void updateValidUntil(long certificateId, LocalDate newValidUntil) {
        int updated = jdbc.update(
                "UPDATE certificate SET valid_until = ? WHERE id = ? AND valid_flag = 'Y'",
                Date.valueOf(newValidUntil), certificateId);
        if (updated != 1) {
            throw new IllegalStateException("展期更新证书行数异常: " + updated);
        }
    }

    /** 一般缺陷逾期未合格复查：证书失效。 */
    public void markExpired(long certificateId) {
        int updated = jdbc.update("""
                        UPDATE certificate SET status = 'EXPIRED', valid_flag = NULL
                        WHERE id = ? AND valid_flag = 'Y'
                        """, certificateId);
        if (updated != 1) {
            throw new IllegalStateException("证书失效更新行数异常: " + updated);
        }
    }

    /** 列出某船某类型全部版本（证书版本链复算用）。 */
    public List<Certificate> findVersions(long shipId, SurveyType type) {
        return jdbc.query("""
                        SELECT * FROM certificate
                        WHERE ship_id = ? AND type = ? ORDER BY version
                        """, MAPPER, shipId, type.name());
    }
}
