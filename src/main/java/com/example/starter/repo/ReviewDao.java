package com.example.starter.repo;

import com.example.starter.domain.ReviewStatus;
import com.example.starter.domain.ReviewType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 审查案件与迟到日志事件的数据访问。
 */
@Repository
public class ReviewDao {

    /** 审查案件行。 */
    public record ReviewRow(long id, long voyageId, ReviewType reviewType, ReviewStatus status,
                            String reason, String resolution, Instant createdAt, Instant resolvedAt) {
    }

    private final JdbcTemplate jdbc;

    public ReviewDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 立案，返回案件 ID。 */
    public long insertReview(long voyageId, ReviewType type, String reason) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO review_case (voyage_id, review_type, status, reason, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, voyageId);
            ps.setString(2, type.name());
            ps.setString(3, ReviewStatus.OPEN.name());
            ps.setString(4, reason);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按 ID 查询案件。 */
    public Optional<ReviewRow> findById(long reviewId) {
        List<ReviewRow> rows = jdbc.query(
                "SELECT id, voyage_id, review_type, status, reason, resolution, created_at, resolved_at"
                        + " FROM review_case WHERE id = ?",
                (rs, n) -> mapReview(rs), reviewId);
        return rows.stream().findFirst();
    }

    /** 航次是否存在未办结的审查案件。 */
    public boolean hasOpenReview(long voyageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_case WHERE voyage_id = ? AND status = 'OPEN'",
                Integer.class, voyageId);
        return count != null && count > 0;
    }

    /** 办结案件（复核结论为最终口径）。 */
    public void resolve(long reviewId, String resolution, Instant resolvedAt) {
        jdbc.update("UPDATE review_case SET status = 'RESOLVED', resolution = ?, resolved_at = ? WHERE id = ?",
                resolution, Timestamp.from(resolvedAt), reviewId);
    }

    /** 记录结案后迟到日志事件。 */
    public void insertLateEvent(long voyageId, String requestId, String detail) {
        jdbc.update("INSERT INTO late_log_event (voyage_id, request_id, detail, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                voyageId, requestId, detail, Timestamp.from(Instant.now()));
    }

    /** 航次的迟到事件数（用于复核与测试断言）。 */
    public int countLateEvents(long voyageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_log_event WHERE voyage_id = ?", Integer.class, voyageId);
        return count == null ? 0 : count;
    }

    private ReviewRow mapReview(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp resolved = rs.getTimestamp(8);
        return new ReviewRow(rs.getLong(1), rs.getLong(2),
                ReviewType.valueOf(rs.getString(3)), ReviewStatus.valueOf(rs.getString(4)),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                resolved == null ? null : resolved.toInstant());
    }
}
