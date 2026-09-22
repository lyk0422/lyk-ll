package com.example.starter.persistence;

import com.example.starter.domain.TrackSnapshot;
import com.example.starter.domain.TrackStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的轨迹当前态数据访问。
 */
@Repository
public class JdbcTrackRepository implements TrackRepository {

    private final JdbcTemplate jdbc;

    public JdbcTrackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static final RowMapper<TrackSnapshot> MAPPER = (rs, n) -> new TrackSnapshot(
            rs.getString("track_uid"),
            rs.getString("target_category"),
            TrackStatus.valueOf(rs.getString("status")),
            rs.getDouble("confidence"),
            rs.getString("last_detection_id"),
            (Double) rs.getObject("last_x"),
            (Double) rs.getObject("last_y"),
            (Double) rs.getObject("last_z"),
            rs.getLong("last_observed_at"),
            rs.getLong("version"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String COLUMNS = """
            track_uid, target_category, status, confidence, last_detection_id,
            last_x, last_y, last_z, last_observed_at, version, created_at, updated_at
            """;

    @Override
    public Optional<TrackSnapshot> findByUid(String trackUid) {
        List<TrackSnapshot> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM track WHERE track_uid = ?",
                MAPPER, trackUid);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<TrackSnapshot> lockByUid(String trackUid) {
        List<TrackSnapshot> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM track WHERE track_uid = ? FOR UPDATE",
                MAPPER, trackUid);
        return rows.stream().findFirst();
    }

    @Override
    public List<TrackSnapshot> findCandidates(String category) {
        // 仅非终态、且至少有一个观测位置的轨迹可参与门限关联。
        return jdbc.query("SELECT " + COLUMNS + " FROM track "
                        + "WHERE target_category = ? AND status <> 'RECYCLED' "
                        + "AND last_x IS NOT NULL ORDER BY id",
                MAPPER, category);
    }

    @Override
    public List<TrackSnapshot> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM track ORDER BY id", MAPPER);
    }

    @Override
    public void insert(TrackSnapshot s) {
        jdbc.update("""
                        INSERT INTO track (track_uid, target_category, status, confidence,
                            last_detection_id, last_x, last_y, last_z, last_observed_at,
                            version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                s.trackUid(), s.category(), s.status().name(), s.confidence(),
                s.lastDetectionId(), s.x(), s.y(), s.z(), s.lastObservedAt(),
                s.version(), s.createdAt(), s.updatedAt());
    }

    @Override
    public int compareAndUpdate(String trackUid, long expectedVersion, TrackSnapshot s) {
        // 版本号条件更新：数据库行锁保证并发下只有一个请求成功，affected=0 即版本冲突。
        return jdbc.update("""
                        UPDATE track SET status = ?, confidence = ?, last_detection_id = ?,
                            last_x = ?, last_y = ?, last_z = ?, last_observed_at = ?,
                            version = version + 1, updated_at = ?
                        WHERE track_uid = ? AND version = ?
                        """,
                s.status().name(), s.confidence(), s.lastDetectionId(),
                s.x(), s.y(), s.z(), s.lastObservedAt(), s.updatedAt(),
                trackUid, expectedVersion);
    }

    @Override
    public long nextTrackNumber() {
        // 行锁内自增取号，保证并发创建时轨迹号按提交顺序唯一分配（重放确定性）。
        Long current = jdbc.queryForObject(
                "SELECT next_val FROM track_seq WHERE id = 1 FOR UPDATE", Long.class);
        long next = current == null ? 1L : current;
        jdbc.update("UPDATE track_seq SET next_val = ? WHERE id = 1", next + 1);
        return next;
    }
}
