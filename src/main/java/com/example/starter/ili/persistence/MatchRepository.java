package com.example.starter.ili.persistence;

import com.example.starter.ili.model.Grade;
import com.example.starter.ili.model.ReviewStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 匹配对数据访问：插入、批次内查询与复核结论写入。
 */
@Repository
public class MatchRepository {

    private static final RowMapper<MatchRow> MAPPER = (rs, n) -> new MatchRow(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getLong("old_reading_id"),
            rs.getLong("new_reading_id"),
            rs.getBigDecimal("distance_m"),
            rs.getBigDecimal("growth_rate_pct"),
            Grade.valueOf(rs.getString("grade")),
            ReviewStatus.valueOf(rs.getString("review_status")),
            rs.getString("review_grade") == null ? null : Grade.valueOf(rs.getString("review_grade")),
            rs.getString("reviewer"),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public MatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条匹配对（PENDING 复核状态），返回生成主键。
     */
    public long insert(long batchId, long oldReadingId, long newReadingId,
                       BigDecimal distanceM, BigDecimal growthRatePct, Grade grade) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO match_pair (batch_id, old_reading_id, new_reading_id,"
                            + " distance_m, growth_rate_pct, grade, review_status)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, batchId);
            ps.setLong(2, oldReadingId);
            ps.setLong(3, newReadingId);
            ps.setBigDecimal(4, distanceM);
            ps.setBigDecimal(5, growthRatePct);
            ps.setString(6, grade.name());
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /**
     * 查询批次内全部匹配对，按主键排序。
     */
    public List<MatchRow> findByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM match_pair WHERE batch_id = ? ORDER BY id", MAPPER, batchId);
    }

    /**
     * 按主键查询匹配对。
     */
    public Optional<MatchRow> findById(long id) {
        return jdbc.query("SELECT * FROM match_pair WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 写入复核结论（仅当仍处于 PENDING 时生效）。
     *
     * @return 实际写入返回 true；匹配对不存在或已复核返回 false
     */
    public boolean applyReview(long matchId, ReviewStatus status, Grade reviewGrade,
                               String reviewer, LocalDateTime reviewedAt) {
        int updated = jdbc.update(
                "UPDATE match_pair SET review_status = ?, review_grade = ?, reviewer = ?, reviewed_at = ?"
                        + " WHERE id = ? AND review_status = 'PENDING'",
                status.name(),
                reviewGrade == null ? null : reviewGrade.name(),
                reviewer,
                Timestamp.valueOf(reviewedAt),
                matchId);
        return updated == 1;
    }
}
