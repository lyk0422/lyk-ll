package com.example.starter.cosmetic.child.persistence;

import com.example.starter.cosmetic.child.domain.AgeTier;
import com.example.starter.cosmetic.child.domain.ChildChangeType;
import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.domain.ChildReviewDetail;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildActiveSlot;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import com.example.starter.cosmetic.domain.FormulationIngredient;
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
 * 基于 JdbcTemplate 的儿童备案修订适配器。
 *
 * <p>补正失效、公示期满生效、版本替代均以带状态/版本谓词的单条原子 UPDATE 实现，
 * 受影响行数决定唯一归属；与成人备案表完全隔离。</p>
 */
@Repository
public class JdbcChildFilingPort implements ChildFilingPort {

    private static final TypeReference<List<FormulationIngredient>> FORMULATION_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<AgeTier>> TIER_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ChildSafetyReport>> REPORT_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public JdbcChildFilingPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ChildFilingRecord> MAPPER = (rs, n) -> new ChildFilingRecord(
            rs.getLong("id"),
            rs.getString("filing_no"),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getInt("version_no"),
            rs.getString("parent_filing_no"),
            ChildChangeType.valueOf(rs.getString("change_type")),
            ChildFilingStatus.valueOf(rs.getString("status")),
            rs.getInt("age_from_months"),
            rs.getInt("age_to_months"),
            JsonSupport.fromJson(rs.getString("required_tiers_json"), TIER_LIST_TYPE),
            JsonSupport.fromJson(rs.getString("formulation_json"), FORMULATION_TYPE),
            JsonSupport.fromJson(rs.getString("claims_json"), STRING_LIST_TYPE),
            JsonSupport.fromJson(rs.getString("safety_reports_json"), REPORT_LIST_TYPE),
            rs.getString("age_label_text"),
            rs.getString("age_label_digest"),
            rs.getString("age_rule_version"),
            rs.getString("packaging_text"),
            rs.getString("request_id"),
            rs.getTimestamp("submitted_at_utc").toInstant(),
            toInstant(rs.getTimestamp("form_reviewed_at_utc")),
            toLocalDate(rs.getDate("public_notice_date_utc")),
            toInstant(rs.getTimestamp("public_expires_at_utc")),
            toInstant(rs.getTimestamp("effective_at_utc")),
            toInstant(rs.getTimestamp("voided_at_utc")),
            rs.getString("reject_reason"));

    private static final RowMapper<ChildReviewDetail> REVIEW_MAPPER = (rs, n) -> new ChildReviewDetail(
            rs.getLong("id"),
            rs.getString("filing_no"),
            rs.getInt("version_no"),
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
        jdbc.update("UPDATE child_filing_sequence SET seq = seq + 1 WHERE id = 1");
        Long seq = jdbc.queryForObject("SELECT seq FROM child_filing_sequence WHERE id = 1", Long.class);
        if (seq == null) {
            throw new IllegalStateException("儿童备案编号顺序表未初始化");
        }
        return seq;
    }

    @Override
    public void insert(ChildFilingRecord r) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO child_filing
                      (filing_no, product_code, product_name, version_no, parent_filing_no,
                       change_type, status, age_from_months, age_to_months, required_tiers_json,
                       formulation_json, claims_json, safety_reports_json,
                       age_label_text, age_label_digest, age_rule_version,
                       packaging_text, request_id, submitted_at_utc, form_reviewed_at_utc,
                       public_notice_date_utc, public_expires_at_utc, effective_at_utc,
                       voided_at_utc, reject_reason, created_at_utc, updated_at_utc)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            setString(ps, i++, r.filingNo());
            ps.setString(i++, r.productCode());
            ps.setString(i++, r.productName());
            ps.setInt(i++, r.versionNo());
            setString(ps, i++, r.parentFilingNo());
            ps.setString(i++, r.changeType().name());
            ps.setString(i++, r.status().name());
            ps.setInt(i++, r.ageFromMonths());
            ps.setInt(i++, r.ageToMonths());
            ps.setString(i++, JsonSupport.toJson(r.requiredTiers()));
            ps.setString(i++, JsonSupport.toJson(r.formulation()));
            ps.setString(i++, JsonSupport.toJson(r.claims()));
            ps.setString(i++, JsonSupport.toJson(r.safetyReports()));
            ps.setString(i++, r.ageLabelText());
            ps.setString(i++, r.ageLabelDigest());
            ps.setString(i++, r.ageRuleVersion());
            setString(ps, i++, r.packagingText());
            ps.setString(i++, r.requestId());
            ps.setTimestamp(i++, Timestamp.from(r.submittedAtUtc()));
            setInstant(ps, i++, r.formReviewedAtUtc());
            setDate(ps, i++, r.publicNoticeDateUtc());
            setInstant(ps, i++, r.publicExpiresAtUtc());
            setInstant(ps, i++, r.effectiveAtUtc());
            setInstant(ps, i++, r.voidedAtUtc());
            setString(ps, i++, r.rejectReason());
            Instant now = Instant.now();
            ps.setTimestamp(i++, Timestamp.from(now));
            ps.setTimestamp(i, Timestamp.from(now));
            return ps;
        }, keyHolder);
    }

    @Override
    public Optional<ChildFilingRecord> findById(long id) {
        return jdbc.query(selectAll() + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<ChildFilingRecord> findLatestByNo(String filingNo) {
        return jdbc.query(selectAll() + " WHERE filing_no = ? ORDER BY version_no DESC LIMIT 1",
                MAPPER, filingNo).stream().findFirst();
    }

    @Override
    public Optional<ChildFilingRecord> findVersion(String filingNo, int versionNo) {
        return jdbc.query(selectAll() + " WHERE filing_no = ? AND version_no = ?",
                MAPPER, filingNo, versionNo).stream().findFirst();
    }

    @Override
    public List<ChildFilingRecord> findByProduct(String productCode) {
        return jdbc.query(selectAll() + " WHERE product_code = ? ORDER BY version_no ASC, id ASC",
                MAPPER, productCode);
    }

    private static String selectAll() {
        return """
                SELECT id, filing_no, product_code, product_name, version_no, parent_filing_no,
                       change_type, status, age_from_months, age_to_months, required_tiers_json,
                       formulation_json, claims_json, safety_reports_json,
                       age_label_text, age_label_digest, age_rule_version,
                       packaging_text, request_id, submitted_at_utc, form_reviewed_at_utc,
                       public_notice_date_utc, public_expires_at_utc, effective_at_utc,
                       voided_at_utc, reject_reason
                FROM child_filing
                """;
    }

    @Override
    public Optional<ChildActiveSlot> findActiveSlot(String productCode) {
        return jdbc.query(
                "SELECT product_code, filing_no, version_no, since_utc "
                        + "FROM child_product_active WHERE product_code = ?",
                (rs, n) -> new ChildActiveSlot(
                        rs.getString("product_code"),
                        rs.getString("filing_no"),
                        rs.getInt("version_no"),
                        rs.getTimestamp("since_utc").toInstant()),
                productCode).stream().findFirst();
    }

    @Override
    public void occupySlot(String productCode, String filingNo, int versionNo, Instant now) {
        jdbc.update("""
                INSERT INTO child_product_active (product_code, filing_no, version_no, since_utc)
                VALUES (?, ?, ?, ?)
                """, productCode, filingNo, versionNo, Timestamp.from(now));
    }

    @Override
    public void replaceSlot(String productCode, String filingNo, int versionNo, Instant now) {
        int updated = jdbc.update("""
                UPDATE child_product_active
                SET filing_no = ?, version_no = ?, since_utc = ?
                WHERE product_code = ?
                """, filingNo, versionNo, Timestamp.from(now), productCode);
        if (updated != 1) {
            throw new IllegalStateException("儿童有效备案槽不存在，无法切换: " + productCode);
        }
    }

    @Override
    public void releaseSlot(String productCode) {
        jdbc.update("DELETE FROM child_product_active WHERE product_code = ?", productCode);
    }

    @Override
    public boolean expireToEffectiveIfLatestPublic(long id, String filingNo, Instant effectiveAtUtc) {
        // 仅当该修订为 PUBLIC 且确为本编号最新修订（version_no 最大）时生效：
        // 补正已生成更新修订时，旧公示修订不得生效（公示只能使用最新修订）。
        int updated = jdbc.update("""
                UPDATE child_filing
                SET status = 'EFFECTIVE', effective_at_utc = ?, updated_at_utc = ?
                WHERE id = ? AND filing_no = ? AND status = 'PUBLIC'
                  AND version_no = (SELECT MAX(version_no) FROM child_filing WHERE filing_no = ?)
                """, Timestamp.from(effectiveAtUtc), Timestamp.from(Instant.now()),
                id, filingNo, filingNo);
        return updated == 1;
    }

    @Override
    public boolean supersedeEffective(long id, Instant now) {
        int updated = jdbc.update("""
                UPDATE child_filing
                SET status = 'SUPERSEDED', updated_at_utc = ?
                WHERE id = ? AND status = 'EFFECTIVE'
                """, Timestamp.from(now), id);
        return updated == 1;
    }

    @Override
    public boolean voidPendingRevision(long id, Instant now) {
        // 补正可失效“形式驳回”或“公示中（待批准）”的旧修订；已生效/已失效修订不可静默替换。
        // 与公示期满生效竞争：若期满分支已先置 EFFECTIVE，则 0 行，补正事务回滚，唯一终态。
        int updated = jdbc.update("""
                UPDATE child_filing
                SET status = 'VOID', voided_at_utc = ?, updated_at_utc = ?
                WHERE id = ? AND status IN ('FORM_REJECTED', 'PUBLIC')
                """, Timestamp.from(now), Timestamp.from(Instant.now()), id);
        return updated == 1;
    }

    @Override
    public void saveReviews(List<ChildReviewDetail> details) {
        if (details.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO child_review_detail
                  (filing_no, version_no, rule_code, passed, detail, created_at_utc)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                details, details.size(), (ps, d) -> {
                    ps.setString(1, d.filingNo());
                    ps.setInt(2, d.versionNo());
                    ps.setString(3, d.ruleCode());
                    ps.setInt(4, d.passed() ? 1 : 0);
                    ps.setString(5, d.detail());
                    ps.setTimestamp(6, Timestamp.from(d.createdAtUtc()));
                });
    }

    @Override
    public List<ChildReviewDetail> findReviews(String filingNo, Integer versionNo) {
        if (versionNo == null) {
            return jdbc.query(
                    "SELECT id, filing_no, version_no, rule_code, passed, detail, created_at_utc "
                            + "FROM child_review_detail WHERE filing_no = ? ORDER BY id ASC",
                    REVIEW_MAPPER, filingNo);
        }
        return jdbc.query(
                "SELECT id, filing_no, version_no, rule_code, passed, detail, created_at_utc "
                        + "FROM child_review_detail WHERE filing_no = ? AND version_no = ? ORDER BY id ASC",
                REVIEW_MAPPER, filingNo, versionNo);
    }

    @Override
    public List<ChildFilingRecord> findDuePublicFilings(Instant now) {
        return jdbc.query(selectAll()
                        + " WHERE status = 'PUBLIC' AND public_expires_at_utc <= ? ORDER BY id ASC",
                MAPPER, Timestamp.from(now));
    }

    @Override
    public int nextProductVersion(String productCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM child_filing WHERE product_code = ?",
                Integer.class, productCode);
        return (max == null ? 0 : max) + 1;
    }
}
