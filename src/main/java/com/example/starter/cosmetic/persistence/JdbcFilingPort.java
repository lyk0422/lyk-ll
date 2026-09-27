package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.domain.ChangeType;
import com.example.starter.cosmetic.domain.ChildBandDossier;
import com.example.starter.cosmetic.domain.FilingCategory;
import com.example.starter.cosmetic.domain.FilingRecord;
import com.example.starter.cosmetic.domain.FilingStatus;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.domain.ReviewDetail;
import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.port.ActiveSlot;
import com.example.starter.cosmetic.port.FilingPort;
import com.example.starter.cosmetic.support.JsonSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的备案记录适配器。
 *
 * <p>所有状态竞争（公示期满 vs 异议受理、版本替代）均以带状态谓词的单条原子
 * UPDATE 实现，受影响行数决定唯一终态归属。</p>
 */
@Repository
public class JdbcFilingPort implements FilingPort {

    private static final TypeReference<List<FormulationIngredient>> FORMULATION_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<TestReportRecord>> REPORT_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ChildBandDossier>> CHILD_DOSSIER_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public JdbcFilingPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FilingRecord> MAPPER = (rs, n) -> new FilingRecord(
            rs.getLong("id"),
            rs.getString("filing_no"),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getInt("version_no"),
            rs.getString("parent_filing_no"),
            ChangeType.valueOf(rs.getString("change_type")),
            FilingStatus.valueOf(rs.getString("status")),
            JsonSupport.fromJson(rs.getString("formulation_json"), FORMULATION_TYPE),
            JsonSupport.fromJson(rs.getString("claims_json"), STRING_LIST_TYPE),
            JsonSupport.fromJson(rs.getString("reports_json"), REPORT_LIST_TYPE),
            rs.getString("packaging_text"),
            rs.getString("request_id"),
            rs.getTimestamp("submitted_at_utc").toInstant(),
            toInstant(rs.getTimestamp("form_reviewed_at_utc")),
            toLocalDate(rs.getDate("public_notice_date_utc")),
            toInstant(rs.getTimestamp("public_expires_at_utc")),
            toInstant(rs.getTimestamp("objection_received_at_utc")),
            toInstant(rs.getTimestamp("effective_at_utc")),
            toInstant(rs.getTimestamp("withdrawn_at_utc")),
            rs.getString("reject_reason"),
            FilingCategory.valueOf(rs.getString("filing_category")),
            toInteger(rs, "age_lower_months"),
            toInteger(rs, "age_upper_months"),
            rs.getString("age_rule_version"),
            rs.getString("child_reports_json") == null ? null
                    : JsonSupport.fromJson(rs.getString("child_reports_json"), CHILD_DOSSIER_TYPE),
            rs.getString("label_digest"));

    private static Integer toInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<ReviewDetail> REVIEW_MAPPER = (rs, n) -> new ReviewDetail(
            rs.getLong("id"),
            rs.getString("filing_no"),
            rs.getInt("version_no"),
            rs.getString("stage"),
            rs.getString("rule_code"),
            rs.getInt("passed") == 1,
            rs.getString("detail"),
            rs.getTimestamp("created_at_utc").toInstant());

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

    @Override
    public long nextFilingSequence() {
        jdbc.update("UPDATE filing_sequence SET seq = seq + 1 WHERE id = 1");
        Long seq = jdbc.queryForObject("SELECT seq FROM filing_sequence WHERE id = 1", Long.class);
        if (seq == null) {
            throw new IllegalStateException("备案编号顺序表未初始化");
        }
        return seq;
    }

    @Override
    public void insert(FilingRecord r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO filing_record
                      (filing_no, product_code, product_name, version_no, parent_filing_no,
                       change_type, status, formulation_json, claims_json, reports_json,
                       packaging_text, request_id, submitted_at_utc, form_reviewed_at_utc,
                       public_notice_date_utc, public_expires_at_utc, objection_received_at_utc,
                       effective_at_utc, withdrawn_at_utc, reject_reason,
                       filing_category, age_lower_months, age_upper_months, age_rule_version,
                       child_reports_json, label_texts_json, label_digest,
                       created_at_utc, updated_at_utc)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            setString(ps, i++, r.filingNo());
            ps.setString(i++, r.productCode());
            ps.setString(i++, r.productName());
            ps.setInt(i++, r.versionNo());
            setString(ps, i++, r.parentFilingNo());
            ps.setString(i++, r.changeType().name());
            ps.setString(i++, r.status().name());
            ps.setString(i++, JsonSupport.toJson(r.formulation()));
            ps.setString(i++, JsonSupport.toJson(r.claims()));
            ps.setString(i++, JsonSupport.toJson(r.reports()));
            setString(ps, i++, r.packagingText());
            ps.setString(i++, r.requestId());
            ps.setTimestamp(i++, Timestamp.from(r.submittedAtUtc()));
            setInstant(ps, i++, r.formReviewedAtUtc());
            setDate(ps, i++, r.publicNoticeDateUtc());
            setInstant(ps, i++, r.publicExpiresAtUtc());
            setInstant(ps, i++, r.objectionReceivedAtUtc());
            setInstant(ps, i++, r.effectiveAtUtc());
            setInstant(ps, i++, r.withdrawnAtUtc());
            setString(ps, i++, r.rejectReason());
            ps.setString(i++, r.filingCategory().name());
            setInteger(ps, i++, r.ageLowerMonths());
            setInteger(ps, i++, r.ageUpperMonths());
            setString(ps, i++, r.ageRuleVersion());
            setString(ps, i++, r.childDossiers() == null ? null : JsonSupport.toJson(r.childDossiers()));
            setString(ps, i++, labelTextsJson(r));
            setString(ps, i++, r.labelDigest());
            Instant now = Instant.now();
            ps.setTimestamp(i++, Timestamp.from(now));
            ps.setTimestamp(i, Timestamp.from(now));
            return ps;
        }, keyHolder);
    }

    /** 由冻结的儿童材料清单派生标识文本快照 JSON（按分龄档）。 */
    private static String labelTextsJson(FilingRecord r) {
        if (r.childDossiers() == null) {
            return null;
        }
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        r.childDossiers().forEach(d -> labels.put(d.bandCode(), d.labelText()));
        return JsonSupport.toJson(labels);
    }

    private static void setInteger(PreparedStatement ps, int idx, Integer v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, v);
        }
    }

    @Override
    public void update(FilingRecord r) {
        int updated = jdbc.update("""
                UPDATE filing_record SET
                  filing_no = ?, product_code = ?, product_name = ?, version_no = ?,
                  parent_filing_no = ?, change_type = ?, status = ?,
                  formulation_json = ?, claims_json = ?, reports_json = ?,
                  packaging_text = ?, request_id = ?, submitted_at_utc = ?,
                  form_reviewed_at_utc = ?, public_notice_date_utc = ?,
                  public_expires_at_utc = ?, objection_received_at_utc = ?,
                  effective_at_utc = ?, withdrawn_at_utc = ?, reject_reason = ?,
                  filing_category = ?, age_lower_months = ?, age_upper_months = ?,
                  age_rule_version = ?, child_reports_json = ?, label_texts_json = ?,
                  label_digest = ?,
                  updated_at_utc = ?
                WHERE id = ?
                """,
                r.filingNo(), r.productCode(), r.productName(), r.versionNo(),
                r.parentFilingNo(), r.changeType().name(), r.status().name(),
                JsonSupport.toJson(r.formulation()), JsonSupport.toJson(r.claims()),
                JsonSupport.toJson(r.reports()), r.packagingText(), r.requestId(),
                Timestamp.from(r.submittedAtUtc()),
                ts(r.formReviewedAtUtc()), date(r.publicNoticeDateUtc()),
                ts(r.publicExpiresAtUtc()), ts(r.objectionReceivedAtUtc()),
                ts(r.effectiveAtUtc()), ts(r.withdrawnAtUtc()), r.rejectReason(),
                r.filingCategory().name(), r.ageLowerMonths(), r.ageUpperMonths(),
                r.ageRuleVersion(),
                r.childDossiers() == null ? null : JsonSupport.toJson(r.childDossiers()),
                labelTextsJson(r), r.labelDigest(),
                Timestamp.from(Instant.now()), r.id());
        if (updated != 1) {
            throw new IllegalStateException("备案版本更新失败，id=" + r.id());
        }
    }

    private static Timestamp ts(Instant v) {
        return v == null ? null : Timestamp.from(v);
    }

    private static java.sql.Date date(LocalDate v) {
        return v == null ? null : java.sql.Date.valueOf(v);
    }

    @Override
    public Optional<FilingRecord> findById(long id) {
        return jdbc.query(selectAll() + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<FilingRecord> findLatestByNo(String filingNo) {
        return jdbc.query(selectAll() + " WHERE filing_no = ? ORDER BY version_no DESC LIMIT 1",
                MAPPER, filingNo).stream().findFirst();
    }

    @Override
    public Optional<FilingRecord> findVersion(String filingNo, int versionNo) {
        return jdbc.query(selectAll() + " WHERE filing_no = ? AND version_no = ?",
                MAPPER, filingNo, versionNo).stream().findFirst();
    }

    @Override
    public List<FilingRecord> findByProduct(String productCode) {
        return jdbc.query(selectAll() + " WHERE product_code = ? ORDER BY version_no ASC, id ASC",
                MAPPER, productCode);
    }

    private static String selectAll() {
        return """
                SELECT id, filing_no, product_code, product_name, version_no, parent_filing_no,
                       change_type, status, formulation_json, claims_json, reports_json,
                       packaging_text, request_id, submitted_at_utc, form_reviewed_at_utc,
                       public_notice_date_utc, public_expires_at_utc, objection_received_at_utc,
                       effective_at_utc, withdrawn_at_utc, reject_reason,
                       filing_category, age_lower_months, age_upper_months, age_rule_version,
                       child_reports_json, label_texts_json, label_digest
                FROM filing_record
                """;
    }

    @Override
    public Optional<ActiveSlot> findActiveSlot(String productCode) {
        return jdbc.query(
                "SELECT product_code, filing_no, version_no, since_utc "
                        + "FROM product_active_filing WHERE product_code = ?",
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
                INSERT INTO product_active_filing (product_code, filing_no, version_no, since_utc)
                VALUES (?, ?, ?, ?)
                """, productCode, filingNo, versionNo, Timestamp.from(now));
    }

    @Override
    public void replaceSlot(String productCode, String filingNo, int versionNo, Instant now) {
        int updated = jdbc.update("""
                UPDATE product_active_filing
                SET filing_no = ?, version_no = ?, since_utc = ?
                WHERE product_code = ?
                """, filingNo, versionNo, Timestamp.from(now), productCode);
        if (updated != 1) {
            throw new IllegalStateException("有效备案槽不存在，无法切换: " + productCode);
        }
    }

    @Override
    public void releaseSlot(String productCode) {
        jdbc.update("DELETE FROM product_active_filing WHERE product_code = ?", productCode);
    }

    @Override
    public boolean acceptObjectionIfPublic(long id, Instant objectionAtUtc) {
        int updated = jdbc.update("""
                UPDATE filing_record
                SET status = 'OBJECTION_REVIEW',
                    objection_received_at_utc = ?,
                    updated_at_utc = ?
                WHERE id = ? AND status = 'PUBLIC'
                """, Timestamp.from(objectionAtUtc), Timestamp.from(Instant.now()), id);
        return updated == 1;
    }

    @Override
    public boolean expireToEffectiveIfPublic(long id, Instant effectiveAtUtc) {
        int updated = jdbc.update("""
                UPDATE filing_record
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
                UPDATE filing_record
                SET status = 'SUPERSEDED', updated_at_utc = ?
                WHERE id = ? AND status = 'EFFECTIVE'
                """, Timestamp.from(now), id);
        return updated == 1;
    }

    @Override
    public void markWithdrawn(long id, Instant now, String reason) {
        int updated = jdbc.update("""
                UPDATE filing_record
                SET status = 'WITHDRAWN', withdrawn_at_utc = ?, reject_reason = ?, updated_at_utc = ?
                WHERE id = ?
                """, Timestamp.from(now), reason, Timestamp.from(Instant.now()), id);
        if (updated != 1) {
            throw new IllegalStateException("注销状态更新失败，id=" + id);
        }
    }

    @Override
    public void markRejectedAfterObjection(long id, Instant now, String reason) {
        int updated = jdbc.update("""
                UPDATE filing_record
                SET status = 'REJECTED_AFTER_OBJECTION', reject_reason = ?, updated_at_utc = ?
                WHERE id = ?
                """, reason, Timestamp.from(Instant.now()), id);
        if (updated != 1) {
            throw new IllegalStateException("异议驳回状态更新失败，id=" + id);
        }
    }

    @Override
    public void reopenPublic(long id) {
        int updated = jdbc.update("""
                UPDATE filing_record SET status = 'PUBLIC', updated_at_utc = ?
                WHERE id = ? AND status = 'OBJECTION_REVIEW'
                """, Timestamp.from(Instant.now()), id);
        if (updated != 1) {
            throw new IllegalStateException("异议不成立回到公示失败，id=" + id);
        }
    }

    @Override
    public void saveReviews(List<ReviewDetail> details) {
        if (details.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO review_detail
                  (filing_no, version_no, stage, rule_code, passed, detail, created_at_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                details, details.size(), (ps, d) -> {
                    ps.setString(1, d.filingNo());
                    ps.setInt(2, d.versionNo());
                    ps.setString(3, d.stage());
                    ps.setString(4, d.ruleCode());
                    ps.setInt(5, d.passed() ? 1 : 0);
                    ps.setString(6, d.detail());
                    ps.setTimestamp(7, Timestamp.from(d.createdAtUtc()));
                });
    }

    @Override
    public List<ReviewDetail> findReviews(String filingNo, Integer versionNo) {
        if (versionNo == null) {
            return jdbc.query(
                    "SELECT id, filing_no, version_no, stage, rule_code, passed, detail, created_at_utc "
                            + "FROM review_detail WHERE filing_no = ? ORDER BY id ASC",
                    REVIEW_MAPPER, filingNo);
        }
        return jdbc.query(
                "SELECT id, filing_no, version_no, stage, rule_code, passed, detail, created_at_utc "
                        + "FROM review_detail WHERE filing_no = ? AND version_no = ? ORDER BY id ASC",
                REVIEW_MAPPER, filingNo, versionNo);
    }

    @Override
    public List<FilingRecord> findDuePublicFilings(Instant now) {
        return jdbc.query(selectAll()
                        + " WHERE status = 'PUBLIC' AND public_expires_at_utc <= ? ORDER BY id ASC",
                MAPPER, Timestamp.from(now));
    }

    @Override
    public boolean hasWithdrawnRecord(String productCode) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM filing_record WHERE product_code = ? AND status = 'WITHDRAWN'",
                Integer.class, productCode);
        return cnt != null && cnt > 0;
    }

    @Override
    public int nextProductVersion(String productCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM filing_record WHERE product_code = ?",
                Integer.class, productCode);
        return (max == null ? 0 : max) + 1;
    }

    @Override
    public List<FilingRecord> findAllVersions(String filingNo) {
        return jdbc.query(selectAll() + " WHERE filing_no = ? ORDER BY version_no ASC",
                MAPPER, filingNo);
    }

    @Override
    public int supersedeRejectedRevisions(String filingNo, Instant now) {
        return jdbc.update("""
                UPDATE filing_record
                SET status = 'SUPERSEDED', updated_at_utc = ?
                WHERE filing_no = ? AND status = 'FORM_REJECTED'
                """, Timestamp.from(now), filingNo);
    }
}
