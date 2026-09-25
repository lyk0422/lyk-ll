package com.example.starter.fishery.db;

import com.example.starter.fishery.domain.ReviewConclusion;
import com.example.starter.fishery.domain.ReviewReason;
import com.example.starter.fishery.domain.ReviewStatus;
import com.example.starter.fishery.db.support.GeneratedKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 人工复核案件访问。
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ReviewRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String conclusion = rs.getString("conclusion");
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new ReviewRow(
                rs.getLong("id"),
                rs.getString("trip_code"),
                rs.getString("vessel_code"),
                ReviewReason.valueOf(rs.getString("reason")),
                ReviewStatus.valueOf(rs.getString("status")),
                conclusion == null ? null : ReviewConclusion.valueOf(conclusion),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(),
                decidedAt == null ? null : decidedAt.toInstant());
    }

    /** 按 id 查案件（行锁由 {@link #findByIdForUpdate} 提供）。 */
    public Optional<ReviewRow> findById(Long id) {
        return queryOne(
                "SELECT id, trip_code, vessel_code, reason, status, conclusion, note, created_at, decided_at "
                        + "FROM review_case WHERE id = ?", id);
    }

    /** 事务内行锁读取案件。 */
    public Optional<ReviewRow> findByIdForUpdate(Long id) {
        return queryOne(
                "SELECT id, trip_code, vessel_code, reason, status, conclusion, note, created_at, decided_at "
                        + "FROM review_case WHERE id = ? FOR UPDATE", id);
    }

    private Optional<ReviewRow> queryOne(String sql, Object... args) {
        List<ReviewRow> rows = jdbc.query(sql, ReviewRepository::mapRow, args);
        return rows.stream().findFirst();
    }

    /** 立复核案件。 */
    public long insert(String tripCode, String vesselCode, ReviewReason reason, String note, Instant now) {
        return GeneratedKeys.insertAndReturnKey(
                jdbc,
                "INSERT INTO review_case(trip_code, vessel_code, reason, status, conclusion, note, "
                        + "created_at, decided_at) VALUES (?, ?, ?, 'OPEN', NULL, ?, ?, NULL)",
                tripCode, vesselCode, reason.name(), note, Timestamp.from(now)).longValue();
    }

    /** 写入复核结论。 */
    public void close(Long id, ReviewConclusion conclusion, String note, Instant decidedAt) {
        jdbc.update("UPDATE review_case SET status = 'CLOSED', conclusion = ?, note = ?, decided_at = ? "
                + "WHERE id = ?", conclusion.name(), note, Timestamp.from(decidedAt), id);
    }

    /** 查航次全部案件。 */
    public List<ReviewRow> findByTrip(String tripCode) {
        return jdbc.query(
                "SELECT id, trip_code, vessel_code, reason, status, conclusion, note, created_at, decided_at "
                        + "FROM review_case WHERE trip_code = ? ORDER BY id",
                ReviewRepository::mapRow, tripCode);
    }

    /** 该船未决案件数（冻结解除条件：为 0 才解冻）。 */
    public long countOpenForVessel(String vesselCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM review_case WHERE vessel_code = ? AND status = 'OPEN'",
                Long.class, vesselCode);
        return count == null ? 0L : count;
    }

    /** 航次未决案件数。 */
    public long countOpenForTrip(String tripCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM review_case WHERE trip_code = ? AND status = 'OPEN'",
                Long.class, tripCode);
        return count == null ? 0L : count;
    }
}
