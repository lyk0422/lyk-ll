package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.domain.AgeLabel;
import com.example.starter.cosmetic.domain.ChangeType;
import com.example.starter.cosmetic.domain.ChildrenFilingRecord;
import com.example.starter.cosmetic.domain.ChildrenFilingStatus;
import com.example.starter.cosmetic.domain.ChildrenSafetyReport;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.port.ActiveSlot;
import com.example.starter.cosmetic.port.ChildrenFilingPort;
import com.example.starter.cosmetic.support.JsonSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的儿童备案记录适配器。
 *
 * <p>补正失效旧修订、公示期满生效、版本替代等状态竞争均以带状态谓词的单条
 * 原子 UPDATE 实现，受影响行数决定唯一归属。</p>
 */
@Repository
public class JdbcChildrenFilingPort implements ChildrenFilingPort {

    private static final TypeReference<List<FormulationIngredient>> FORMULATION_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ChildrenSafetyReport>> SAFETY_REPORT_LIST_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<AgeLabel>> LABEL_LIST_TYPE = new TypeReference<>() {
    };

    private static final RowMapper<ChildrenFilingRecord> MAPPER = (rs, n) -> new ChildrenFilingRecord(
            rs.getLong("id"),
            rs.getString("filing_no"),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getInt("version_no"),
            rs.getInt("revision_no"),
            rs.getString("parent_filing_no"),
            ChangeType.valueOf(rs.getString("change_type")),
            ChildrenFilingStatus.valueOf(rs.getString("status")),
            rs.getInt("age_lower_months"),
            rs.getInt("age_upper_months"),
            JsonSupport.fromJson(rs.getString("formulation_json"), FORMULATION_TYPE),
            JsonSupport.fromJson(rs.getString("claims_json"), STRING_LIST_TYPE),
            JsonSupport.fromJson(rs.getString("safety_reports_json"), SAFETY_REPORT_LIST_TYPE),
            JsonSupport.fromJson(rs.getString("labels_json"), LABEL_LIST_TYPE),
            rs.getString("label_digest"),
            rs.getInt("rule_version"),
            rs.getString("packaging_text"),
            rs.getString("request_id"),
            rs.getTimestamp("submitted_at_utc").toInstant(),
            toLocalDate(rs.getDate("public_notice_date_utc")),
            toInstant(rs.getTimestamp("public_expires_at_utc")),
            toInstant(rs.getTimestamp("effective_at_utc")));

    private final JdbcTemplate jdbc;

    public JdbcChildrenFilingPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static LocalDate toLocalDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }

    private static void setInstant(PreparedStatement ps, int idx, Instant v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.TIMESTAMP);
        } else {
            ps.setTimestamp(idx, Timestamp.from(v));
        }
    }

    private static void setDate(PreparedStatement ps, int idx, LocalDate v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.DATE);
        } else {
            ps.setDate(idx, java.sql.Date.valueOf(v));
        }
    }

    private static void setString(PreparedStatement ps, int idx, String v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.VARCHAR);
        } else {
            ps.setString(idx, v);
        }
    }

    private static String selectAll() {
        return """
                SELECT id, filing_no, product_code, product_name, version_no, revision_no,
                       parent_filing_no, change_type, status, age_lower_months, age_upper_months,
                       formulation_json, claims_json, safety_reports_json, labels_json,
                       label_digest, rule_version, packaging_text, request_id, submitted_at_utc,
                       public_notice_date_utc, public_expires_at_utc, effective_at_utc
                FROM children_filing
                """;
    }

    @Override
    public long nextFilingSequence() {
        jdbc.update("UPDATE children_filing_sequence SET seq = seq + 1 WHERE id = 1");
        Long seq = jdbc.queryForObject("SELECT seq FROM children_filing_sequence WHERE id = 1", Long.class);
        if (seq == null) {
            throw new IllegalStateException("儿童备案编号顺序表未初始化");
        }
        return seq;
    }

    @Override
    public void insert(ChildrenFilingRecord r) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO children_filing
                      (filing_no, product_code, product_name, version_no, revision_no,
                       parent_filing_no, change_type, status, age_lower_months, age_upper_months,
                       formulation_json, claims_json, safety_reports_json, labels_json,
                       label_digest, rule_version, packaging_text, request_id, submitted_at_utc,
                       public_notice_date_utc, public_expires_at_utc, effective_at_utc,
                       created_at_utc, updated_at_utc)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """);
            int i = 1;
            ps.setString(i++, r.filingNo());
            ps.setString(i++, r.productCode());
            ps.setString(i++, r.productName());
            ps.setInt(i++, r.versionNo());
            ps.setInt(i++, r.revisionNo());
            setString(ps, i++, r.parentFilingNo());
            ps.setString(i++, r.changeType().name());
            ps.setString(i++, r.status().name());
            ps.setInt(i++, r.ageLowerMonths());
            ps.setInt(i++, r.ageUpperMonths());
            ps.setString(i++, JsonSupport.toJson(r.formulation()));
            ps.setString(i++, JsonSupport.toJson(r.claims()));
            ps.setString(i++, JsonSupport.toJson(r.safetyReports()));
            ps.setString(i++, JsonSupport.toJson(r.labels()));
            ps.setString(i++, r.labelDigest());
            ps.setInt(i++, r.ruleVersion());
            setString(ps, i++, r.packagingText());
            ps.setString(i++, r.requestId());
            ps.setTimestamp(i++, Timestamp.from(r.submittedAtUtc()));
            setDate(ps, i++, r.publicNoticeDateUtc());
            setInstant(ps, i++, r.publicExpiresAtUtc());
            setInstant(ps, i++, r.effectiveAtUtc());
            Instant now = Instant.now();
            ps.setTimestamp(i++, Timestamp.from(now));
            ps.setTimestamp(i, Timestamp.from(now));
            return ps;
        });
    }

    @Override
    public Optional<ChildrenFilingRecord> findById(long id) {
        return jdbc.query(selectAll() + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<ChildrenFilingRecord> findLatestByNo(String filingNo) {
        return jdbc.query(selectAll()
                        + " WHERE filing_no = ? ORDER BY version_no DESC, revision_no DESC LIMIT 1",
                MAPPER, filingNo).stream().findFirst();
    }

    @Override
    public Optional<ChildrenFilingRecord> findByNoAndVersion(String filingNo, int versionNo) {
        return jdbc.query(selectAll() + " WHERE filing_no = ? AND version_no = ?",
                MAPPER, filingNo, versionNo).stream().findFirst();
    }

    @Override
    public Optional<ChildrenFilingRecord> findVersion(String filingNo, int versionNo, int revisionNo) {
        return jdbc.query(selectAll() + " WHERE filing_no = ? AND version_no = ? AND revision_no = ?",
                MAPPER, filingNo, versionNo, revisionNo).stream().findFirst();
    }

    @Override
    public List<ChildrenFilingRecord> findRevisions(String filingNo) {
        return jdbc.query(selectAll()
                        + " WHERE filing_no = ? ORDER BY version_no ASC, revision_no ASC",
                MAPPER, filingNo);
    }

    @Override
    public List<ChildrenFilingRecord> findByProduct(String productCode) {
        return jdbc.query(selectAll()
                        + " WHERE product_code = ? ORDER BY version_no ASC, revision_no ASC, id ASC",
                MAPPER, productCode);
    }

    @Override
    public int currentVersion(String productCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM children_filing WHERE product_code = ?",
                Integer.class, productCode);
        return max == null ? 0 : max;
    }

    @Override
    public Optional<ActiveSlot> findActiveSlot(String productCode) {
        return jdbc.query(
                "SELECT product_code, filing_no, version_no, since_utc "
                        + "FROM children_active_filing WHERE product_code = ?",
                (rs, n) -> new ActiveSlot(
                        rs.getString("product_code"),
                        rs.getString("filing_no"),
                        rs.getInt("version_no"),
                        rs.getTimestamp("since_utc").toInstant()),
                productCode).stream().findFirst();
    }

    @Override
    public void occupySlot(String productCode, String filingNo, int versionNo, Instant now) {
        jdbc.update("""
                INSERT INTO children_active_filing (product_code, filing_no, version_no, since_utc)
                VALUES (?, ?, ?, ?)
                """, productCode, filingNo, versionNo, Timestamp.from(now));
    }

    @Override
    public void replaceSlot(String productCode, String filingNo, int versionNo, Instant now) {
        int updated = jdbc.update("""
                UPDATE children_active_filing
                SET filing_no = ?, version_no = ?, since_utc = ?
                WHERE product_code = ?
                """, filingNo, versionNo, Timestamp.from(now), productCode);
        if (updated != 1) {
            throw new IllegalStateException("儿童有效备案槽不存在，无法切换: " + productCode);
        }
    }

    @Override
    public boolean supersedePublicRevision(long id, Instant now) {
        int updated = jdbc.update("""
                UPDATE children_filing
                SET status = 'REVISION_SUPERSEDED', updated_at_utc = ?
                WHERE id = ? AND status = 'PUBLIC'
                """, Timestamp.from(now), id);
        return updated == 1;
    }

    @Override
    public boolean expireToEffectiveIfPublic(long id, Instant effectiveAtUtc) {
        int updated = jdbc.update("""
                UPDATE children_filing
                SET status = 'EFFECTIVE',
                    effective_at_utc = ?,
                    updated_at_utc = ?
                WHERE id = ? AND status = 'PUBLIC'
                """, Timestamp.from(effectiveAtUtc), Timestamp.from(Instant.now()), id);
        return updated == 1;
    }

    @Override
    public boolean supersedeEffective(long id, Instant now) {
        int updated = jdbc.update("""
                UPDATE children_filing
                SET status = 'SUPERSEDED', updated_at_utc = ?
                WHERE id = ? AND status = 'EFFECTIVE'
                """, Timestamp.from(now), id);
        return updated == 1;
    }

    @Override
    public List<ChildrenFilingRecord> findDuePublicFilings(Instant now) {
        return jdbc.query(selectAll()
                        + " WHERE status = 'PUBLIC' AND public_expires_at_utc <= ? ORDER BY id ASC",
                MAPPER, Timestamp.from(now));
    }
}
