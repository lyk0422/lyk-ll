package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.domain.LabelDrawRecord;
import com.example.starter.cosmetic.domain.PackagingBatchRecord;
import com.example.starter.cosmetic.domain.PrintRunRecord;
import com.example.starter.cosmetic.domain.PrintRunStatus;
import com.example.starter.cosmetic.port.LabelPrintPort;
import com.example.starter.cosmetic.support.JsonSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的包装标签印次台账适配器。
 *
 * <p>领用/销毁/禁用均以带状态与版本谓词的单条原子 UPDATE 实现，
 * 受影响行数决定竞争归属；余额条件（印刷总数-已领-已毁 &gt;= 数量）
 * 直接在 UPDATE 谓词中判定，保证并发下张数守恒。</p>
 */
@Repository
public class JdbcLabelPrintPort implements LabelPrintPort {

    private static final TypeReference<Map<String, String>> BAND_DIGEST_TYPE = new TypeReference<>() {
    };

    private static final RowMapper<PrintRunRecord> PRINT_RUN_MAPPER = (rs, n) -> new PrintRunRecord(
            rs.getLong("id"),
            rs.getString("print_run_no"),
            rs.getString("product_code"),
            rs.getString("filing_no"),
            rs.getInt("filing_version_no"),
            rs.getString("label_digest"),
            rs.getString("band_digests_json") == null ? null
                    : JsonSupport.fromJson(rs.getString("band_digests_json"), BAND_DIGEST_TYPE),
            rs.getInt("printed_count"),
            rs.getInt("drawn_count"),
            rs.getInt("destroyed_count"),
            PrintRunStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getString("disabled_reason"),
            toInstant(rs.getTimestamp("disabled_at_utc")),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("updated_at_utc").toInstant());

    private static final RowMapper<LabelDrawRecord> DRAW_MAPPER = (rs, n) -> new LabelDrawRecord(
            rs.getLong("id"),
            rs.getString("print_run_no"),
            rs.getString("batch_no"),
            rs.getString("product_code"),
            rs.getInt("quantity"),
            rs.getString("request_id"),
            rs.getTimestamp("drawn_at_utc").toInstant(),
            rs.getInt("risk_flag") == 1,
            rs.getString("risk_note"),
            toInstant(rs.getTimestamp("risk_at_utc")));

    private final JdbcTemplate jdbc;

    public JdbcLabelPrintPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String selectPrintRun() {
        return """
                SELECT id, print_run_no, product_code, filing_no, filing_version_no,
                       label_digest, band_digests_json, printed_count, drawn_count,
                       destroyed_count, status, version, request_id,
                       disabled_reason, disabled_at_utc, created_at_utc, updated_at_utc
                FROM label_print_run
                """;
    }

    @Override
    public void insertPrintRun(PrintRunRecord r) {
        jdbc.update("""
                INSERT INTO label_print_run
                  (print_run_no, product_code, filing_no, filing_version_no,
                   label_digest, band_digests_json, printed_count, drawn_count,
                   destroyed_count, status, version, request_id,
                   disabled_reason, disabled_at_utc, created_at_utc, updated_at_utc)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                r.printRunNo(), r.productCode(), r.filingNo(), r.filingVersionNo(),
                r.labelDigest(),
                r.bandDigests() == null ? null : JsonSupport.toJson(r.bandDigests()),
                r.printedCount(), r.drawnCount(), r.destroyedCount(),
                r.status().name(), r.version(), r.requestId(),
                r.disabledReason(),
                r.disabledAtUtc() == null ? null : Timestamp.from(r.disabledAtUtc()),
                Timestamp.from(r.createdAtUtc()), Timestamp.from(r.updatedAtUtc()));
    }

    @Override
    public Optional<PrintRunRecord> findPrintRun(String printRunNo) {
        return jdbc.query(selectPrintRun() + " WHERE print_run_no = ?",
                PRINT_RUN_MAPPER, printRunNo).stream().findFirst();
    }

    @Override
    public boolean drawIfActive(String printRunNo, int expectedVersion, int quantity, Instant now) {
        int updated = jdbc.update("""
                UPDATE label_print_run
                SET drawn_count = drawn_count + ?,
                    version = version + 1,
                    updated_at_utc = ?
                WHERE print_run_no = ?
                  AND status = 'ACTIVE'
                  AND version = ?
                  AND printed_count - drawn_count - destroyed_count >= ?
                """, quantity, Timestamp.from(now), printRunNo, expectedVersion, quantity);
        return updated == 1;
    }

    @Override
    public boolean destroyIfEnough(String printRunNo, int expectedVersion, int quantity, Instant now) {
        int updated = jdbc.update("""
                UPDATE label_print_run
                SET destroyed_count = destroyed_count + ?,
                    version = version + 1,
                    updated_at_utc = ?
                WHERE print_run_no = ?
                  AND version = ?
                  AND printed_count - drawn_count - destroyed_count >= ?
                """, quantity, Timestamp.from(now), printRunNo, expectedVersion, quantity);
        return updated == 1;
    }

    @Override
    public int disableActiveByFiling(String filingNo, int filingVersionNo, String reason, Instant now) {
        return jdbc.update("""
                UPDATE label_print_run
                SET status = 'DISABLED',
                    version = version + 1,
                    disabled_reason = ?,
                    disabled_at_utc = ?,
                    updated_at_utc = ?
                WHERE filing_no = ? AND filing_version_no = ? AND status = 'ACTIVE'
                """, reason, Timestamp.from(now), Timestamp.from(now), filingNo, filingVersionNo);
    }

    @Override
    public Optional<PackagingBatchRecord> findBatch(String batchNo) {
        return jdbc.query(
                "SELECT batch_no, product_code, created_at_utc FROM packaging_batch WHERE batch_no = ?",
                (rs, n) -> new PackagingBatchRecord(
                        rs.getString("batch_no"),
                        rs.getString("product_code"),
                        rs.getTimestamp("created_at_utc").toInstant()),
                batchNo).stream().findFirst();
    }

    @Override
    public void insertBatch(PackagingBatchRecord batch) {
        jdbc.update("""
                INSERT INTO packaging_batch (batch_no, product_code, created_at_utc)
                VALUES (?, ?, ?)
                """, batch.batchNo(), batch.productCode(), Timestamp.from(batch.createdAtUtc()));
    }

    @Override
    public void insertDraw(LabelDrawRecord d) {
        jdbc.update("""
                INSERT INTO label_draw
                  (print_run_no, batch_no, product_code, quantity, request_id,
                   drawn_at_utc, risk_flag, risk_note, risk_at_utc)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                d.printRunNo(), d.batchNo(), d.productCode(), d.quantity(), d.requestId(),
                Timestamp.from(d.drawnAtUtc()), d.riskFlag() ? 1 : 0, d.riskNote(),
                d.riskAtUtc() == null ? null : Timestamp.from(d.riskAtUtc()));
    }

    @Override
    public List<LabelDrawRecord> findDrawsByPrintRun(String printRunNo) {
        return jdbc.query("""
                SELECT id, print_run_no, batch_no, product_code, quantity, request_id,
                       drawn_at_utc, risk_flag, risk_note, risk_at_utc
                FROM label_draw WHERE print_run_no = ? ORDER BY id ASC
                """, DRAW_MAPPER, printRunNo);
    }

    @Override
    public int flagRiskByFiling(String filingNo, int filingVersionNo, String note, Instant now) {
        return jdbc.update("""
                UPDATE label_draw
                SET risk_flag = 1, risk_note = ?, risk_at_utc = ?
                WHERE risk_flag = 0 AND print_run_no IN (
                    SELECT print_run_no FROM label_print_run
                    WHERE filing_no = ? AND filing_version_no = ?
                )
                """, note, Timestamp.from(now), filingNo, filingVersionNo);
    }

    @Override
    public void insertDestroy(String printRunNo, int quantity, String requestId, Instant destroyedAtUtc) {
        jdbc.update("""
                INSERT INTO label_destroy (print_run_no, quantity, request_id, destroyed_at_utc)
                VALUES (?, ?, ?, ?)
                """, printRunNo, quantity, requestId, Timestamp.from(destroyedAtUtc));
    }
}
