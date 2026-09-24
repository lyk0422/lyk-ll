package com.example.starter.berth;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 靠泊窗口及引用数据的 JDBC 仓储。所有 SQL 参数化，时间统一为 UTC 时间戳。
 */
@Repository
public class BerthWindowRepository {

    private static final RowMapper<BerthWindow> WINDOW_MAPPER = BerthWindowRepository::mapWindow;

    private final JdbcTemplate jdbc;

    public BerthWindowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新申请，返回生成主键。request_id 唯一约束由数据库保证。
     */
    public long insert(BerthWindow window) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO berth_window (request_id, fingerprint, vessel_name, berth_id, start_time,"
                            + " end_time, draft, pilots_required, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, window.requestId());
            ps.setString(2, window.fingerprint());
            ps.setString(3, window.vesselName());
            ps.setLong(4, window.berthId());
            ps.setTimestamp(5, toTs(window.startTime()));
            ps.setTimestamp(6, toTs(window.endTime()));
            ps.setBigDecimal(7, window.draft());
            ps.setInt(8, window.pilotsRequired());
            ps.setString(9, window.status().name());
            ps.setTimestamp(10, toTs(window.createdAt()));
            ps.setTimestamp(11, toTs(window.updatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未返回生成主键");
        }
        return key.longValue();
    }

    public Optional<BerthWindow> findByRequestId(String requestId) {
        List<BerthWindow> rows = jdbc.query(
                "SELECT * FROM berth_window WHERE request_id = ?", WINDOW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public Optional<BerthWindow> findById(long id) {
        List<BerthWindow> rows = jdbc.query(
                "SELECT * FROM berth_window WHERE id = ?", WINDOW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 更新状态与变更时刻，返回受影响行数。
     */
    public int updateStatus(long id, BerthWindowStatus status, Instant updatedAt) {
        return jdbc.update("UPDATE berth_window SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), toTs(updatedAt), id);
    }

    /**
     * 仅当当前状态属于期望集合时才更新（用于并发安全的状态机迁移），返回受影响行数。
     */
    public int updateStatusIfIn(long id, BerthWindowStatus target, List<BerthWindowStatus> expected,
            Instant updatedAt) {
        StringBuilder sql = new StringBuilder(
                "UPDATE berth_window SET status = ?, updated_at = ? WHERE id = ? AND status IN (");
        for (int i = 0; i < expected.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(')');
        Object[] args = new Object[expected.size() + 3];
        args[0] = target.name();
        args[1] = toTs(updatedAt);
        args[2] = id;
        for (int i = 0; i < expected.size(); i++) {
            args[3 + i] = expected.get(i).name();
        }
        return jdbc.update(sql.toString(), args);
    }

    /**
     * 查询同一泊位上与 [start, end) 存在内部重叠、且状态为已批准或已生效的窗口（排除自身）。
     * 半开区间重叠条件：existing.start < end AND existing.end > start，端点相接不算冲突。
     */
    public List<BerthWindow> findBlockingOnBerth(long berthId, Instant start, Instant end, long excludeId) {
        return jdbc.query(
                "SELECT * FROM berth_window WHERE berth_id = ? AND id <> ?"
                        + " AND status IN ('APPROVED', 'ACTIVE')"
                        + " AND start_time < ? AND end_time > ?",
                WINDOW_MAPPER, berthId, excludeId, toTs(end), toTs(start));
    }

    /**
     * 查询与 [start, end) 重叠的已批准/已生效窗口已占用的引航员ID集合（排除指定窗口）。
     */
    public List<Long> findBusyPilotIds(Instant start, Instant end, long excludeWindowId) {
        return jdbc.query(
                "SELECT DISTINCT wp.pilot_id FROM window_pilot wp"
                        + " JOIN berth_window bw ON bw.id = wp.window_id"
                        + " WHERE bw.id <> ? AND bw.status IN ('APPROVED', 'ACTIVE')"
                        + " AND bw.start_time < ? AND bw.end_time > ?",
                (rs, rowNum) -> rs.getLong(1), excludeWindowId, toTs(end), toTs(start));
    }

    public List<Long> findAllPilotIds() {
        return jdbc.query("SELECT id FROM pilot ORDER BY id", (rs, rowNum) -> rs.getLong(1));
    }

    public List<Long> findPilotIdsOfWindow(long windowId) {
        return jdbc.query("SELECT pilot_id FROM window_pilot WHERE window_id = ? ORDER BY pilot_id",
                (rs, rowNum) -> rs.getLong(1), windowId);
    }

    public void assignPilot(long windowId, long pilotId) {
        jdbc.update("INSERT INTO window_pilot (window_id, pilot_id) VALUES (?, ?)", windowId, pilotId);
    }

    public void releasePilots(long windowId) {
        jdbc.update("DELETE FROM window_pilot WHERE window_id = ?", windowId);
    }

    /**
     * 查询泊位限深（米）；泊位不存在时返回空。
     */
    public Optional<BigDecimal> findBerthDepthLimit(long berthId) {
        List<BigDecimal> rows = jdbc.query("SELECT depth_limit FROM berth WHERE id = ?",
                (rs, rowNum) -> rs.getBigDecimal(1), berthId);
        return rows.stream().findFirst();
    }

    /**
     * 判断 [start, end] 是否完整落在某段允许靠泊时段内（端点恰等于边界合法）。
     */
    public boolean isWithinAllowedWindow(Instant start, Instant end) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allowed_window WHERE start_time <= ? AND end_time >= ?",
                Integer.class, toTs(start), toTs(end));
        return count != null && count > 0;
    }

    /**
     * Instant -> 数据库时间戳：按 UTC 墙钟写入，避免依赖 JVM 默认时区。
     */
    private static Timestamp toTs(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * 数据库时间戳 -> Instant：按 UTC 墙钟解读，与 {@link #toTs} 及初始化脚本字面量一致。
     */
    private static Instant toInstant(Timestamp ts) {
        return ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private static BerthWindow mapWindow(ResultSet rs, int rowNum) throws SQLException {
        return new BerthWindow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("vessel_name"),
                rs.getLong("berth_id"),
                toInstant(rs.getTimestamp("start_time")),
                toInstant(rs.getTimestamp("end_time")),
                rs.getBigDecimal("draft"),
                rs.getInt("pilots_required"),
                BerthWindowStatus.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }
}
