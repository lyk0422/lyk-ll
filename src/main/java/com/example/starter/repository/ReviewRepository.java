package com.example.starter.repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.domain.FreezeReview;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 监管复核（解除冻结）数据访问。
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入复核记录并返回生成主键。
     */
    public long insert(String requestId, String fingerprint, long mineId,
            String reviewer, Instant reviewedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO freeze_review (request_id, fingerprint, mine_id, reviewer, reviewed_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, mineId);
            ps.setString(4, reviewer);
            ps.setTimestamp(5, Timestamp.from(reviewedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按幂等键查询。
     */
    public Optional<FreezeReview> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM freeze_review WHERE request_id = ?", this::map, requestId)
                .stream().findFirst();
    }

    private FreezeReview map(ResultSet rs, int rowNum) throws SQLException {
        return new FreezeReview(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("mine_id"),
                rs.getString("reviewer"),
                rs.getTimestamp("reviewed_at").toInstant());
    }
}
