package com.example.starter.ili;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * ili_match_pair 表的数据访问。
 */
@Repository
public class MatchPairRepository {

    private static final RowMapper<PairRow> MAPPER = MatchPairRepository::mapRow;

    private final JdbcTemplate jdbc;

    public MatchPairRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一个匹配对（复核状态 PENDING）并返回生成的主键。
     */
    public long insert(long batchId, long oldReadingId, long newReadingId,
            java.math.BigDecimal distanceM, java.math.BigDecimal growthRatePctPerYear, Grade grade) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO ili_match_pair (batch_id, old_reading_id, new_reading_id,"
                            + " distance_m, growth_rate_pct_per_year, grade, review_status)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'PENDING')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setLong(2, oldReadingId);
            ps.setLong(3, newReadingId);
            ps.setBigDecimal(4, distanceM);
            ps.setBigDecimal(5, growthRatePctPerYear);
            ps.setString(6, grade.name());
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("匹配对主键生成失败");
        }
        return key.longValue();
    }

    /**
     * 查询批次的全部匹配对，按主键升序。
     */
    public List<PairRow> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM ili_match_pair WHERE batch_id = ? ORDER BY id",
                MAPPER, batchId);
    }

    /**
     * 按主键与批次查询匹配对。
     */
    public Optional<PairRow> findByIdAndBatchId(long id, long batchId) {
        return jdbc.query("SELECT * FROM ili_match_pair WHERE id = ? AND batch_id = ?",
                MAPPER, id, batchId).stream().findFirst();
    }

    /**
     * 写入复核结论。
     */
    public void updateReview(long id, ReviewStatus reviewStatus, Grade finalGrade,
            String reviewer, String reviewComment, Instant reviewedAt) {
        jdbc.update("UPDATE ili_match_pair SET review_status = ?, final_grade = ?,"
                        + " reviewer = ?, review_comment = ?, reviewed_at = ? WHERE id = ?",
                reviewStatus.name(), finalGrade.name(), reviewer, reviewComment,
                Timestamp.from(reviewedAt), id);
    }

    private static PairRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        String finalGrade = rs.getString("final_grade");
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new PairRow(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getLong("old_reading_id"),
                rs.getLong("new_reading_id"),
                rs.getBigDecimal("distance_m"),
                rs.getBigDecimal("growth_rate_pct_per_year"),
                Grade.valueOf(rs.getString("grade")),
                ReviewStatus.valueOf(rs.getString("review_status")),
                finalGrade == null ? null : Grade.valueOf(finalGrade),
                rs.getString("reviewer"),
                rs.getString("review_comment"),
                reviewedAt == null ? null : reviewedAt.toInstant());
    }
}
