package com.example.starter.ili;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 对齐批次、缺陷读数与匹配对的 JDBC 数据访问。
 * 行级并发控制依赖 {@code SELECT ... FOR UPDATE} 与条件更新，须在事务内调用。
 */
@Repository
public class AlignmentRepository {

    private final JdbcTemplate jdbc;

    public AlignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批次行记录。
     */
    public record BatchRow(
            long id,
            String requestId,
            String fingerprint,
            String segmentId,
            String oldRunId,
            String newRunId,
            LocalDate oldRunDate,
            LocalDate newRunDate,
            BatchStatus status,
            LocalDateTime createdAt,
            LocalDateTime sealedAt) {
    }

    /**
     * 匹配对行记录（含两侧读数明细）。
     */
    public record MatchRow(
            long id,
            long batchId,
            long oldReadingId,
            long newReadingId,
            BigDecimal mileageDiffM,
            BigDecimal growthRate,
            Grade grade,
            ReviewStatus reviewStatus,
            Grade finalGrade,
            String reviewedBy,
            LocalDateTime reviewedAt,
            String oldDefectRef,
            String newDefectRef,
            BigDecimal oldMileageM,
            BigDecimal newMileageM,
            BigDecimal oldDepthPct,
            BigDecimal newDepthPct) {
    }

    /**
     * 未匹配读数行记录。
     */
    public record ReadingRow(
            long id,
            String runSide,
            String defectRef,
            BigDecimal mileageM,
            BigDecimal depthPct,
            MatchOutcome outcome) {
    }

    private static final RowMapper<BatchRow> BATCH_MAPPER = (rs, n) -> mapBatch(rs);

    private static BatchRow mapBatch(ResultSet rs) throws SQLException {
        Timestamp sealedAt = rs.getTimestamp("sealed_at");
        return new BatchRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("segment_id"),
                rs.getString("old_run_id"),
                rs.getString("new_run_id"),
                rs.getDate("old_run_date").toLocalDate(),
                rs.getDate("new_run_date").toLocalDate(),
                BatchStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toLocalDateTime(),
                sealedAt == null ? null : sealedAt.toLocalDateTime());
    }

    private static final RowMapper<MatchRow> MATCH_MAPPER = (rs, n) -> new MatchRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("old_reading_id"),
            rs.getLong("new_reading_id"),
            rs.getBigDecimal("mileage_diff_m"),
            rs.getBigDecimal("growth_rate"),
            Grade.valueOf(rs.getString("grade")),
            ReviewStatus.valueOf(rs.getString("review_status")),
            rs.getString("final_grade") == null ? null : Grade.valueOf(rs.getString("final_grade")),
            rs.getString("reviewed_by"),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime(),
            rs.getString("old_defect_ref"),
            rs.getString("new_defect_ref"),
            rs.getBigDecimal("old_mileage_m"),
            rs.getBigDecimal("new_mileage_m"),
            rs.getBigDecimal("old_depth_pct"),
            rs.getBigDecimal("new_depth_pct"));

    private static final String MATCH_SELECT =
            "SELECT m.id, m.batch_id, m.old_reading_id, m.new_reading_id, m.mileage_diff_m,"
            + " m.growth_rate, m.grade, m.review_status, m.final_grade, m.reviewed_by, m.reviewed_at,"
            + " o.defect_ref AS old_defect_ref, n.defect_ref AS new_defect_ref,"
            + " o.mileage_m AS old_mileage_m, n.mileage_m AS new_mileage_m,"
            + " o.depth_pct AS old_depth_pct, n.depth_pct AS new_depth_pct"
            + " FROM defect_match m"
            + " JOIN defect_reading o ON o.id = m.old_reading_id"
            + " JOIN defect_reading n ON n.id = m.new_reading_id";

    /**
     * 按幂等键查询批次（不加锁，用于响应组装）。
     */
    public Optional<BatchRow> findBatchByRequestId(String requestId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT * FROM alignment_batch WHERE request_id = ?", BATCH_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按幂等键查询批次并加行锁（事务内串行化同键提交）。
     */
    public Optional<BatchRow> lockBatchByRequestId(String requestId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT * FROM alignment_batch WHERE request_id = ? FOR UPDATE", BATCH_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按主键查询批次并加行锁（复核与封板的状态序列化点）。
     */
    public Optional<BatchRow> lockBatchById(long batchId) {
        List<BatchRow> rows = jdbc.query(
                "SELECT * FROM alignment_batch WHERE id = ? FOR UPDATE", BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /**
     * 插入批次，返回生成主键。
     */
    public long insertBatch(String requestId, String fingerprint, String segmentId,
                            String oldRunId, String newRunId,
                            LocalDate oldRunDate, LocalDate newRunDate,
                            LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO alignment_batch"
                    + " (request_id, fingerprint, segment_id, old_run_id, new_run_id,"
                    + "  old_run_date, new_run_date, status, created_at)"
                    + " VALUES (?,?,?,?,?,?,?,'OPEN',?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, segmentId);
            ps.setString(4, oldRunId);
            ps.setString(5, newRunId);
            ps.setDate(6, java.sql.Date.valueOf(oldRunDate));
            ps.setDate(7, java.sql.Date.valueOf(newRunDate));
            ps.setTimestamp(8, Timestamp.valueOf(createdAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 插入缺陷读数，返回生成主键。
     */
    public long insertReading(long batchId, String runSide, String defectRef,
                              BigDecimal mileageM, BigDecimal depthPct, MatchOutcome outcome) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO defect_reading"
                    + " (batch_id, run_side, defect_ref, mileage_m, depth_pct, match_outcome)"
                    + " VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setString(2, runSide);
            ps.setString(3, defectRef);
            ps.setBigDecimal(4, mileageM);
            ps.setBigDecimal(5, depthPct);
            ps.setString(6, outcome.name());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 更新读数的对齐去向。
     */
    public void updateReadingOutcome(long readingId, MatchOutcome outcome) {
        jdbc.update("UPDATE defect_reading SET match_outcome = ? WHERE id = ?",
                outcome.name(), readingId);
    }

    /**
     * 插入匹配对，返回生成主键。
     */
    public long insertMatch(long batchId, long oldReadingId, long newReadingId,
                            BigDecimal mileageDiffM, BigDecimal growthRate, Grade grade,
                            ReviewStatus reviewStatus, Grade finalGrade) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO defect_match"
                    + " (batch_id, old_reading_id, new_reading_id, mileage_diff_m, growth_rate,"
                    + "  grade, review_status, final_grade)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setLong(2, oldReadingId);
            ps.setLong(3, newReadingId);
            ps.setBigDecimal(4, mileageDiffM);
            ps.setBigDecimal(5, growthRate);
            ps.setString(6, grade.name());
            ps.setString(7, reviewStatus.name());
            ps.setString(8, finalGrade.name());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询批次全部匹配对（按主键升序，保证响应稳定）。
     */
    public List<MatchRow> listMatches(long batchId) {
        return jdbc.query(MATCH_SELECT + " WHERE m.batch_id = ? ORDER BY m.id", MATCH_MAPPER, batchId);
    }

    /**
     * 按主键查询批次内匹配对。
     */
    public Optional<MatchRow> findMatch(long batchId, long matchId) {
        List<MatchRow> rows = jdbc.query(
                MATCH_SELECT + " WHERE m.batch_id = ? AND m.id = ?", MATCH_MAPPER, batchId, matchId);
        return rows.stream().findFirst();
    }

    /**
     * 查询批次全部未匹配读数（新发现 / 已消失）。
     */
    public List<ReadingRow> listUnmatchedReadings(long batchId) {
        return jdbc.query(
                "SELECT id, run_side, defect_ref, mileage_m, depth_pct, match_outcome"
                + " FROM defect_reading WHERE batch_id = ? AND match_outcome <> 'MATCHED'"
                + " ORDER BY id",
                (rs, n) -> new ReadingRow(
                        rs.getLong("id"),
                        rs.getString("run_side"),
                        rs.getString("defect_ref"),
                        rs.getBigDecimal("mileage_m"),
                        rs.getBigDecimal("depth_pct"),
                        MatchOutcome.valueOf(rs.getString("match_outcome"))),
                batchId);
    }

    /**
     * 写入复核结论。调用方须已持有批次行锁并确认批次未封板。
     */
    public void updateMatchReview(long matchId, ReviewStatus reviewStatus, Grade finalGrade,
                                  String reviewer, LocalDateTime reviewedAt) {
        jdbc.update(
                "UPDATE defect_match SET review_status = ?, final_grade = ?, reviewed_by = ?, reviewed_at = ?"
                + " WHERE id = ?",
                reviewStatus.name(), finalGrade.name(), reviewer, Timestamp.valueOf(reviewedAt), matchId);
    }

    /**
     * 条件封板：仅当批次仍为 OPEN 时置为 SEALED，返回受影响行数（0 表示已被并发封板）。
     */
    public int sealBatchIfOpen(long batchId, LocalDateTime sealedAt) {
        return jdbc.update(
                "UPDATE alignment_batch SET status = 'SEALED', sealed_at = ? WHERE id = ? AND status = 'OPEN'",
                Timestamp.valueOf(sealedAt), batchId);
    }
}
