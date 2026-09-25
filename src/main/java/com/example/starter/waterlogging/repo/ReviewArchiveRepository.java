package com.example.starter.waterlogging.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.waterlogging.po.ReviewArchive;

/**
 * 复盘归档数据访问：plan_id 唯一约束保证归档终态仅一份记录。
 */
@Repository
public class ReviewArchiveRepository {

    private static final RowMapper<ReviewArchive> MAPPER = (rs, n) -> new ReviewArchive(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            readTime(rs, "archived_at"),
            rs.getString("remark"),
            readTime(rs, "created_at"));

    public ReviewArchiveRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final JdbcTemplate jdbcTemplate;

    private static LocalDateTime readTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    /** 写入归档终态记录。 */
    public long insert(ReviewArchive archive) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO review_archive
                        (plan_id, request_id, fingerprint, archived_at, remark, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, archive.planId());
            ps.setString(2, archive.requestId());
            ps.setString(3, archive.fingerprint());
            ps.setTimestamp(4, Timestamp.valueOf(archive.archivedAt()));
            ps.setString(5, archive.remark());
            ps.setTimestamp(6, Timestamp.valueOf(archive.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按预降单查询归档记录。 */
    public Optional<ReviewArchive> findByPlanId(long planId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, plan_id, request_id, fingerprint, archived_at, remark, created_at
                    FROM review_archive WHERE plan_id = ?
                    """, MAPPER, planId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
