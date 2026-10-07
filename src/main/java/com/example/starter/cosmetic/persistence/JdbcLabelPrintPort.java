package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.domain.FilingCategory;
import com.example.starter.cosmetic.domain.LabelDestruction;
import com.example.starter.cosmetic.domain.LabelIssue;
import com.example.starter.cosmetic.domain.LabelPrintRun;
import com.example.starter.cosmetic.domain.PackagingBatch;
import com.example.starter.cosmetic.domain.PrintRunStatus;
import com.example.starter.cosmetic.port.LabelPrintPort;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的包装标签印次台账适配器。
 *
 * <p>领用/销毁扣减以带版本与余额谓词的单条原子 UPDATE 实现；
 * 禁用与风险关联追加供备案换版/注销事务内调用。</p>
 */
@Repository
public class JdbcLabelPrintPort implements LabelPrintPort {

    private static final TypeReference<Map<String, String>> BAND_DIGESTS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public JdbcLabelPrintPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LabelPrintRun> RUN_MAPPER = (rs, n) -> new LabelPrintRun(
            rs.getLong("id"),
            rs.getString("print_run_no"),
            rs.getString("product_code"),
            rs.getString("filing_no"),
            rs.getInt("filing_version_no"),
            FilingCategory.valueOf(rs.getString("filing_category")),
            rs.getString("label_digest"),
            rs.getString("band_digests_json") == null ? null
                    : JsonSupport.fromJson(rs.getString("band_digests_json"), BAND_DIGESTS_TYPE),
            rs.getInt("printed_count"),
            rs.getInt("issued_count"),
            rs.getInt("destroyed_count"),
            PrintRunStatus.valueOf(rs.getString("status")),
            rs.getInt("version_no"),
            rs.getString("disabled_reason"),
            toInstant(rs.getTimestamp("disabled_at_utc")),
            rs.getString("request_id"),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("updated_at_utc").toInstant());

    private static final RowMapper<LabelIssue> ISSUE_MAPPER = (rs, n) -> new LabelIssue(
            rs.getLong("id"),
            rs.getString("print_run_no"),
            rs.getString("batch_no"),
            rs.getString("product_code"),
            rs.getInt("quantity"),
            rs.getString("request_id"),
            rs.getTimestamp("issued_at_utc").toInstant(),
            rs.getInt("risk_flag") == 1,
            rs.getString("risk_note"),
            toInstant(rs.getTimestamp("risk_at_utc")));

    private static final RowMapper<LabelDestruction> DESTRUCTION_MAPPER = (rs, n) -> new LabelDestruction(
            rs.getLong("id"),
            rs.getString("print_run_no"),
            rs.getInt("quantity"),
            rs.getString("reason"),
            rs.getString("request_id"),
            rs.getTimestamp("destroyed_at_utc").toInstant());

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String selectRun() {
        return """
                SELECT id, print_run_no, product_code, filing_no, filing_version_no, filing_category,
                       label_digest, band_digests_json, printed_count, issued_count, destroyed_count,
                       status, version_no, disabled_reason, disabled_at_utc, request_id,
                       created_at_utc, updated_at_utc
                FROM label_print_run
                """;
    }

    @Override
    public void insertRun(LabelPrintRun r) {
        jdbc.update("""
                INSERT INTO label_print_run
                  (print_run_no, product_code, filing_no, filing_version_no, filing_category,
                   label_digest, band_digests_json, printed_count, issued_count, destroyed_count,
                   status, version_no, disabled_reason, disabled_at_utc, request_id,
                   created_at_utc, updated_at_utc)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                r.printRunNo(), r.productCode(), r.filingNo(), r.filingVersionNo(),
                r.filingCategory().name(), r.labelDigest(),
                r.bandDigests() == null ? null : JsonSupport.toJson(r.bandDigests()),
                r.printedCount(), r.issuedCount(), r.destroyedCount(),
                r.status().name(), r.versionNo(), r.disabledReason(),
                r.disabledAtUtc() == null ? null : Timestamp.from(r.disabledAtUtc()),
                r.requestId(), Timestamp.from(r.createdAtUtc()), Timestamp.from(r.updatedAtUtc()));
    }

    @Override
    public Optional<LabelPrintRun> findRun(String printRunNo) {
        return jdbc.query(selectRun() + " WHERE print_run_no = ?", RUN_MAPPER, printRunNo)
                .stream().findFirst();
    }

    @Override
    public Optional<LabelPrintRun> findRunForUpdate(String printRunNo) {
        return jdbc.query(selectRun() + " WHERE print_run_no = ? FOR UPDATE", RUN_MAPPER, printRunNo)
                .stream().findFirst();
    }

    @Override
    public int applyIssue(String printRunNo, int expectedVersion, int quantity, Instant now) {
        return jdbc.update("""
                UPDATE label_print_run
                SET issued_count = issued_count + ?,
                    version_no = version_no + 1,
                    updated_at_utc = ?
                WHERE print_run_no = ? AND version_no = ? AND status = 'ACTIVE'
                  AND printed_count - issued_count - destroyed_count >= ?
                """, quantity, Timestamp.from(now), printRunNo, expectedVersion, quantity);
    }

    @Override
    public int applyDestruction(String printRunNo, int expectedVersion, int quantity, Instant now) {
        return jdbc.update("""
                UPDATE label_print_run
                SET destroyed_count = destroyed_count + ?,
                    version_no = version_no + 1,
                    updated_at_utc = ?
                WHERE print_run_no = ? AND version_no = ?
                  AND printed_count - issued_count - destroyed_count >= ?
                """, quantity, Timestamp.from(now), printRunNo, expectedVersion, quantity);
    }

    @Override
    public long insertIssue(LabelIssue issue) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO label_issue
                      (print_run_no, batch_no, product_code, quantity, request_id,
                       issued_at_utc, risk_flag, risk_note, risk_at_utc)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, issue.printRunNo());
            ps.setString(2, issue.batchNo());
            ps.setString(3, issue.productCode());
            ps.setInt(4, issue.quantity());
            ps.setString(5, issue.requestId());
            ps.setTimestamp(6, Timestamp.from(issue.issuedAtUtc()));
            ps.setInt(7, issue.riskFlag() ? 1 : 0);
            ps.setString(8, issue.riskNote());
            if (issue.riskAtUtc() == null) {
                ps.setNull(9, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(9, Timestamp.from(issue.riskAtUtc()));
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("领用凭据主键生成失败");
        }
        return key.longValue();
    }

    @Override
    public long insertDestruction(LabelDestruction destruction) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO label_destruction
                      (print_run_no, quantity, reason, request_id, destroyed_at_utc)
                    VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, destruction.printRunNo());
            ps.setInt(2, destruction.quantity());
            ps.setString(3, destruction.reason());
            ps.setString(4, destruction.requestId());
            ps.setTimestamp(5, Timestamp.from(destruction.destroyedAtUtc()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("销毁登记主键生成失败");
        }
        return key.longValue();
    }

    @Override
    public List<LabelIssue> findIssuesByRun(String printRunNo) {
        return jdbc.query("""
                SELECT id, print_run_no, batch_no, product_code, quantity, request_id,
                       issued_at_utc, risk_flag, risk_note, risk_at_utc
                FROM label_issue WHERE print_run_no = ? ORDER BY id ASC
                """, ISSUE_MAPPER, printRunNo);
    }

    @Override
    public List<LabelIssue> findIssuesByBatch(String batchNo) {
        return jdbc.query("""
                SELECT id, print_run_no, batch_no, product_code, quantity, request_id,
                       issued_at_utc, risk_flag, risk_note, risk_at_utc
                FROM label_issue WHERE batch_no = ? ORDER BY id ASC
                """, ISSUE_MAPPER, batchNo);
    }

    @Override
    public List<LabelDestruction> findDestructionsByRun(String printRunNo) {
        return jdbc.query("""
                SELECT id, print_run_no, quantity, reason, request_id, destroyed_at_utc
                FROM label_destruction WHERE print_run_no = ? ORDER BY id ASC
                """, DESTRUCTION_MAPPER, printRunNo);
    }

    @Override
    public Optional<PackagingBatch> findBatch(String batchNo) {
        return jdbc.query("""
                SELECT batch_no, product_code, created_at_utc
                FROM packaging_batch WHERE batch_no = ?
                """,
                (rs, n) -> new PackagingBatch(
                        rs.getString("batch_no"),
                        rs.getString("product_code"),
                        rs.getTimestamp("created_at_utc").toInstant()),
                batchNo).stream().findFirst();
    }

    @Override
    public void insertBatch(String batchNo, String productCode, Instant now) {
        jdbc.update("""
                INSERT INTO packaging_batch (batch_no, product_code, created_at_utc)
                VALUES (?, ?, ?)
                """, batchNo, productCode, Timestamp.from(now));
    }

    @Override
    public int disableActiveRunsByFiling(String filingNo, String reason, Instant now) {
        return jdbc.update("""
                UPDATE label_print_run
                SET status = 'DISABLED',
                    version_no = version_no + 1,
                    disabled_reason = ?,
                    disabled_at_utc = ?,
                    updated_at_utc = ?
                WHERE filing_no = ? AND status = 'ACTIVE'
                """, reason, Timestamp.from(now), Timestamp.from(now), filingNo);
    }

    @Override
    public int flagRiskByFiling(String filingNo, String note, Instant now) {
        return jdbc.update("""
                UPDATE label_issue
                SET risk_flag = 1, risk_note = ?, risk_at_utc = ?
                WHERE risk_flag = 0 AND print_run_no IN (
                    SELECT print_run_no FROM label_print_run WHERE filing_no = ?
                )
                """, note, Timestamp.from(now), filingNo);
    }
}
