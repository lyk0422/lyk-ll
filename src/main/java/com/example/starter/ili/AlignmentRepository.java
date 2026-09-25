package com.example.starter.ili;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 对齐批次、检测回合、缺陷读数与匹配对的数据访问。
 */
@Repository
public class AlignmentRepository {

    private final JdbcTemplate jdbc;

    public AlignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 检测回合行记录。 */
    public record RunRow(long id, long segmentId, String runCode, LocalDate runDate) {
    }

    /** 对齐批次行记录。 */
    public record BatchRow(long id, String requestId, String fingerprint, long segmentId, long run1Id,
            long run2Id, String status, int pairCount, int newCount, int disappearedCount,
            Instant createdAt, Instant sealedAt) {
    }

    /** 缺陷读数行记录。 */
    public record ReadingRow(long id, long batchId, long runId, String defectRef, BigDecimal absMileageM,
            BigDecimal depthPct, String matchStatus) {
    }

    /** 匹配对行记录。 */
    public record PairRow(long id, long batchId, long oldReadingId, long newReadingId,
            BigDecimal mileageDiffM, BigDecimal growthRatePctYear, String grade, String reviewStatus,
            String finalGrade, String reviewer, Instant reviewedAt) {
    }

    private static final RowMapper<RunRow> RUN_MAPPER = (rs, rowNum) -> new RunRow(
            rs.getLong("id"), rs.getLong("segment_id"), rs.getString("run_code"),
            rs.getDate("run_date").toLocalDate());

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, rowNum) -> new BatchRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getLong("segment_id"), rs.getLong("run1_id"), rs.getLong("run2_id"),
            rs.getString("status"), rs.getInt("pair_count"), rs.getInt("new_count"),
            rs.getInt("disappeared_count"), rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("sealed_at")));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, rowNum) -> new ReadingRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("run_id"), rs.getString("defect_ref"),
            rs.getBigDecimal("abs_mileage_m"), rs.getBigDecimal("depth_pct"),
            rs.getString("match_status"));

    private static final RowMapper<PairRow> PAIR_MAPPER = (rs, rowNum) -> new PairRow(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("old_reading_id"),
            rs.getLong("new_reading_id"), rs.getBigDecimal("mileage_diff_m"),
            rs.getBigDecimal("growth_rate_pct_year"), rs.getString("grade"),
            rs.getString("review_status"), rs.getString("final_grade"), rs.getString("reviewer"),
            toInstant(rs.getTimestamp("reviewed_at")));

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final String BATCH_COLUMNS =
            "id, request_id, fingerprint, segment_id, run1_id, run2_id, status, pair_count, "
                    + "new_count, disappeared_count, created_at, sealed_at";

    public Optional<RunRow> findRun(long segmentId, String runCode) {
        List<RunRow> rows = jdbc.query(
                "SELECT id, segment_id, run_code, run_date FROM inspection_run "
                        + "WHERE segment_id = ? AND run_code = ?",
                RUN_MAPPER, segmentId, runCode);
        return rows.stream().findFirst();
    }

    public Optional<RunRow> findRunById(long runId) {
        List<RunRow> rows = jdbc.query(
                "SELECT id, segment_id, run_code, run_date FROM inspection_run WHERE id = ?",
                RUN_MAPPER, runId);
        return rows.stream().findFirst();
    }

    public long insertRun(long segmentId, String runCode, LocalDate runDate, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO inspection_run (segment_id, run_code, run_date, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, segmentId);
            ps.setString(2, runCode);
            ps.setDate(3, Date.valueOf(runDate));
            ps.setTimestamp(4, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<BatchRow> findBatchByRequestId(String requestId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT " + BATCH_COLUMNS + " FROM alignment_batch WHERE request_id = ?",
                BATCH_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public Optional<BatchRow> findBatchById(long batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT " + BATCH_COLUMNS + " FROM alignment_batch WHERE id = ?",
                BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /** 按主键锁定批次行（SELECT ... FOR UPDATE），用于复核与封板的串行化。 */
    public Optional<BatchRow> lockBatchById(long batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT " + BATCH_COLUMNS + " FROM alignment_batch WHERE id = ? FOR UPDATE",
                BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    public long insertBatch(String requestId, String fingerprint, long segmentId, long run1Id, long run2Id,
            String status, int pairCount, int newCount, int disappearedCount, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO alignment_batch (request_id, fingerprint, segment_id, run1_id, run2_id, "
                            + "status, pair_count, new_count, disappeared_count, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, segmentId);
            ps.setLong(4, run1Id);
            ps.setLong(5, run2Id);
            ps.setString(6, status);
            ps.setInt(7, pairCount);
            ps.setInt(8, newCount);
            ps.setInt(9, disappearedCount);
            ps.setTimestamp(10, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long insertReading(long batchId, long runId, String defectRef, BigDecimal absMileageM,
            BigDecimal depthPct, String matchStatus) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO defect_reading (batch_id, run_id, defect_ref, abs_mileage_m, depth_pct, "
                            + "match_status) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setLong(2, runId);
            ps.setString(3, defectRef);
            ps.setBigDecimal(4, absMileageM);
            ps.setBigDecimal(5, depthPct);
            ps.setString(6, matchStatus);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertPair(long batchId, long oldReadingId, long newReadingId, BigDecimal mileageDiffM,
            BigDecimal growthRatePctYear, String grade, String reviewStatus) {
        jdbc.update(
                "INSERT INTO match_pair (batch_id, old_reading_id, new_reading_id, mileage_diff_m, "
                        + "growth_rate_pct_year, grade, review_status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                batchId, oldReadingId, newReadingId, mileageDiffM, growthRatePctYear, grade, reviewStatus);
    }

    public List<ReadingRow> findReadings(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, run_id, defect_ref, abs_mileage_m, depth_pct, match_status "
                        + "FROM defect_reading WHERE batch_id = ? ORDER BY id",
                READING_MAPPER, batchId);
    }

    public List<PairRow> findPairs(long batchId) {
        return jdbc.query(
                "SELECT id, batch_id, old_reading_id, new_reading_id, mileage_diff_m, "
                        + "growth_rate_pct_year, grade, review_status, final_grade, reviewer, reviewed_at "
                        + "FROM match_pair WHERE batch_id = ? ORDER BY id",
                PAIR_MAPPER, batchId);
    }

    public Optional<PairRow> findPair(long pairId) {
        try {
            PairRow row = jdbc.queryForObject(
                    "SELECT id, batch_id, old_reading_id, new_reading_id, mileage_diff_m, "
                            + "growth_rate_pct_year, grade, review_status, final_grade, reviewer, "
                            + "reviewed_at FROM match_pair WHERE id = ?",
                    PAIR_MAPPER, pairId);
            return Optional.ofNullable(row);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int countPendingReviews(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM match_pair WHERE batch_id = ? AND review_status = 'PENDING'",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    public void updatePairReview(long pairId, String reviewStatus, String finalGrade, String reviewer,
            Instant reviewedAt) {
        jdbc.update(
                "UPDATE match_pair SET review_status = ?, final_grade = ?, reviewer = ?, reviewed_at = ? "
                        + "WHERE id = ?",
                reviewStatus, finalGrade, reviewer, Timestamp.from(reviewedAt), pairId);
    }

    public void sealBatch(long batchId, Instant sealedAt) {
        jdbc.update("UPDATE alignment_batch SET status = 'SEALED', sealed_at = ? WHERE id = ?",
                Timestamp.from(sealedAt), batchId);
    }
}
