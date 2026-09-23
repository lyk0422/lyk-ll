package com.example.starter.ct.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 分裂视图告警数据访问（H2，参数化 SQL）。
 */
@Repository
public class AlertRepository {

    private static final RowMapper<AlertRow> ROW_MAPPER = (rs, rowNum) -> new AlertRow(
            rs.getLong("id"), rs.getString("alert_type"), rs.getString("observer_id"), rs.getLong("tree_size"),
            rs.getString("local_root_hash"), rs.getString("reported_root_hash"), rs.getLong("reported_at"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 告警行；时间均为 UTC 纪元毫秒。
     */
    public record AlertRow(long id, String alertType, String observerId, long treeSize, String localRootHash,
            String reportedRootHash, long reportedAt, long createdAt) {
    }

    /**
     * 原子创建告警；同一 treeSize 已存在告警时返回空（唯一约束去重）。
     */
    public Optional<Long> insertIfAbsent(String observerId, long treeSize, String localRootHash,
            String reportedRootHash, long reportedAt, long createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO split_view_alert (alert_type, observer_id, tree_size, local_root_hash,"
                                + " reported_root_hash, reported_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, "SPLIT_VIEW");
                ps.setString(2, observerId);
                ps.setLong(3, treeSize);
                ps.setString(4, localRootHash);
                ps.setString(5, reportedRootHash);
                ps.setLong(6, reportedAt);
                ps.setLong(7, createdAt);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        Number key = keyHolder.getKey();
        return key == null ? Optional.empty() : Optional.of(key.longValue());
    }

    public List<AlertRow> findAll() {
        return jdbc.query(
                "SELECT id, alert_type, observer_id, tree_size, local_root_hash, reported_root_hash,"
                        + " reported_at, created_at FROM split_view_alert ORDER BY id ASC",
                ROW_MAPPER);
    }
}
