package com.example.starter.cosmetic.child.persistence;

import com.example.starter.cosmetic.child.domain.ChildFilingRecord;
import com.example.starter.cosmetic.child.domain.ChildFilingStatus;
import com.example.starter.cosmetic.child.domain.ChildLabel;
import com.example.starter.cosmetic.child.domain.ChildRevisionRecord;
import com.example.starter.cosmetic.child.domain.ChildRevisionStatus;
import com.example.starter.cosmetic.child.domain.ChildRevisionType;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildFilingPort;
import com.example.starter.cosmetic.domain.FormulationIngredient;
import com.example.starter.cosmetic.support.JsonSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的儿童备案适配器。
 *
 * <p>补正失效、公示期满生效、旧修订替代与版本乐观锁均以带谓词的单条原子
 * UPDATE 实现，受影响行数决定唯一结果归属。</p>
 */
@Repository
public class JdbcChildFilingPort implements ChildFilingPort {

    private static final TypeReference<List<FormulationIngredient>> FORMULATION_TYPE =
            new TypeReference<>() {
            };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ChildSafetyReport>> REPORT_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ChildLabel>> LABEL_LIST_TYPE = new TypeReference<>() {
    };

    private static final RowMapper<ChildFilingRecord> FILING_MAPPER = (rs, n) -> new ChildFilingRecord(
            rs.getLong("id"),
            rs.getString("filing_no"),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getInt("version_no"),
            ChildFilingStatus.valueOf(rs.getString("status")),
            rs.getInt("current_revision_no"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("updated_at_utc").toInstant());

    private static final RowMapper<ChildRevisionRecord> REVISION_MAPPER = (rs, n) -> new ChildRevisionRecord(
            rs.getLong("id"),
            rs.getString("filing_no"),
            rs.getInt("revision_no"),
            ChildRevisionType.valueOf(rs.getString("revision_type")),
            ChildRevisionStatus.valueOf(rs.getString("status")),
            rs.getInt("age_lower_months"),
            rs.getInt("age_upper_months"),
            JsonSupport.fromJson(rs.getString("claims_json"), STRING_LIST_TYPE),
            JsonSupport.fromJson(rs.getString("formulation_json"), FORMULATION_TYPE),
            JsonSupport.fromJson(rs.getString("safety_reports_json"), REPORT_LIST_TYPE),
            JsonSupport.fromJson(rs.getString("labels_json"), LABEL_LIST_TYPE),
            rs.getString("rule_version"),
            rs.getString("label_digest"),
            rs.getString("packaging_text"),
            rs.getString("reject_reason"),
            rs.getString("request_id"),
            rs.getTimestamp("submitted_at_utc").toInstant(),
            toInstant(rs.getTimestamp("accepted_at_utc")),
            toInstant(rs.getTimestamp("public_expires_at_utc")),
            toInstant(rs.getTimestamp("effective_at_utc")));

    private final JdbcTemplate jdbc;

    public JdbcChildFilingPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static void setInstant(PreparedStatement ps, int idx, Instant v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(idx, java.sql.Types.TIMESTAMP);
        } else {
            ps.setTimestamp(idx, Timestamp.from(v));
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
    public void insertFiling(ChildFilingRecord r) {
        jdbc.update("""
                INSERT INTO child_filing
                  (filing_no, product_code, product_name, version_no, status,
                   current_revision_no, request_id, created_at_utc, updated_at_utc)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.filingNo(), r.productCode(), r.productName(), r.versionNo(), r.status().name(),
                r.currentRevisionNo(), r.requestId(),
                Timestamp.from(r.createdAtUtc()), Timestamp.from(r.updatedAtUtc()));
    }

    @Override
    public Optional<ChildFilingRecord> findFiling(String filingNo) {
        return jdbc.query(selectFiling() + " WHERE filing_no = ?", FILING_MAPPER, filingNo)
                .stream().findFirst();
    }

    @Override
    public Optional<ChildFilingRecord> findFilingByProduct(String productCode) {
        return jdbc.query(selectFiling() + " WHERE product_code = ?", FILING_MAPPER, productCode)
                .stream().findFirst();
    }

    private static String selectFiling() {
        return """
                SELECT id, filing_no, product_code, product_name, version_no, status,
                       current_revision_no, request_id, created_at_utc, updated_at_utc
                FROM child_filing
                """;
    }

    @Override
    public boolean incrementVersionIfExpected(String filingNo, int expectedVersion, String productName,
                                              ChildFilingStatus newStatus, int currentRevisionNo,
                                              Instant now) {
        int updated = jdbc.update("""
                UPDATE child_filing
                SET version_no = version_no + 1, product_name = ?, status = ?,
                    current_revision_no = ?, updated_at_utc = ?
                WHERE filing_no = ? AND version_no = ?
                """,
                productName, newStatus.name(), currentRevisionNo, Timestamp.from(now),
                filingNo, expectedVersion);
        return updated == 1;
    }

    @Override
    public void updateFilingStatus(String filingNo, ChildFilingStatus status, Instant now) {
        int updated = jdbc.update("""
                UPDATE child_filing SET status = ?, updated_at_utc = ? WHERE filing_no = ?
                """, status.name(), Timestamp.from(now), filingNo);
        if (updated != 1) {
            throw new IllegalStateException("儿童备案状态更新失败: " + filingNo);
        }
    }

    @Override
    public void insertRevision(ChildRevisionRecord r) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO child_filing_revision
                      (filing_no, revision_no, revision_type, status,
                       age_lower_months, age_upper_months,
                       claims_json, formulation_json, safety_reports_json, labels_json,
                       rule_version, label_digest, packaging_text, reject_reason, request_id,
                       submitted_at_utc, accepted_at_utc, public_expires_at_utc, effective_at_utc,
                       created_at_utc, updated_at_utc)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, r.filingNo());
            ps.setInt(i++, r.revisionNo());
            ps.setString(i++, r.revisionType().name());
            ps.setString(i++, r.status().name());
            ps.setInt(i++, r.ageLowerMonths());
            ps.setInt(i++, r.ageUpperMonths());
            ps.setString(i++, JsonSupport.toJson(r.claims()));
            ps.setString(i++, JsonSupport.toJson(r.formulation()));
            ps.setString(i++, JsonSupport.toJson(r.safetyReports()));
            ps.setString(i++, JsonSupport.toJson(r.labels()));
            ps.setString(i++, r.ruleVersion());
            ps.setString(i++, r.labelDigest());
            ps.setString(i++, r.packagingText());
            ps.setString(i++, r.rejectReason());
            ps.setString(i++, r.requestId());
            ps.setTimestamp(i++, Timestamp.from(r.submittedAtUtc()));
            setInstant(ps, i++, r.acceptedAtUtc());
            setInstant(ps, i++, r.publicExpiresAtUtc());
            setInstant(ps, i++, r.effectiveAtUtc());
            Instant now = Instant.now();
            ps.setTimestamp(i++, Timestamp.from(now));
            ps.setTimestamp(i, Timestamp.from(now));
            return ps;
        });
    }

    @Override
    public Optional<ChildRevisionRecord> findRevision(String filingNo, int revisionNo) {
        return jdbc.query(selectRevision() + " WHERE filing_no = ? AND revision_no = ?",
                REVISION_MAPPER, filingNo, revisionNo).stream().findFirst();
    }

    @Override
    public List<ChildRevisionRecord> findRevisions(String filingNo) {
        return jdbc.query(selectRevision() + " WHERE filing_no = ? ORDER BY revision_no ASC",
                REVISION_MAPPER, filingNo);
    }

    private static String selectRevision() {
        return """
                SELECT id, filing_no, revision_no, revision_type, status,
                       age_lower_months, age_upper_months,
                       claims_json, formulation_json, safety_reports_json, labels_json,
                       rule_version, label_digest, packaging_text, reject_reason, request_id,
                       submitted_at_utc, accepted_at_utc, public_expires_at_utc, effective_at_utc
                FROM child_filing_revision
                """;
    }

    @Override
    public boolean invalidateRevisionIfPending(long revisionId, Instant now) {
        int updated = jdbc.update("""
                UPDATE child_filing_revision
                SET status = 'INVALIDATED', updated_at_utc = ?
                WHERE id = ? AND status IN ('PUBLIC', 'REJECTED')
                """, Timestamp.from(now), revisionId);
        return updated == 1;
    }

    @Override
    public boolean effectuateIfPublic(long revisionId, Instant effectiveAtUtc) {
        int updated = jdbc.update("""
                UPDATE child_filing_revision
                SET status = 'EFFECTIVE', effective_at_utc = ?, updated_at_utc = ?
                WHERE id = ? AND status = 'PUBLIC'
                """, Timestamp.from(effectiveAtUtc), Timestamp.from(Instant.now()), revisionId);
        return updated == 1;
    }

    @Override
    public boolean supersedeIfEffective(long revisionId, Instant now) {
        int updated = jdbc.update("""
                UPDATE child_filing_revision
                SET status = 'SUPERSEDED', updated_at_utc = ?
                WHERE id = ? AND status = 'EFFECTIVE'
                """, Timestamp.from(now), revisionId);
        return updated == 1;
    }

    @Override
    public Optional<ChildRevisionRecord> findEffectiveRevision(String filingNo) {
        return jdbc.query(selectRevision() + " WHERE filing_no = ? AND status = 'EFFECTIVE'",
                REVISION_MAPPER, filingNo).stream().findFirst();
    }

    @Override
    public List<ChildRevisionRecord> findDuePublicRevisions(Instant now) {
        return jdbc.query(selectRevision()
                        + " WHERE status = 'PUBLIC' AND public_expires_at_utc <= ? ORDER BY id ASC",
                REVISION_MAPPER, Timestamp.from(now));
    }
}
