package com.example.starter.water;

import java.sql.PreparedStatement;
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
 * 水质监测数据访问层；全部使用参数化 SQL，行锁用于写路径的并发控制。
 */
@Repository
public class WaterRepository {

    private static final RowMapper<TankRow> TANK_MAPPER = (rs, rowNum) -> new TankRow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            TankStatus.valueOf(rs.getString("status")),
            rs.getInt("display_suspended") != 0,
            rs.getInt("auto_disposal_suspended") != 0,
            rs.getInt("data_missing") != 0,
            rs.getInt("consecutive_violations"),
            SensorStatus.valueOf(rs.getString("sensor_status")),
            rs.getTimestamp("sensor_last_seen_at").toInstant());

    private static final RowMapper<AlarmRow> ALARM_MAPPER = (rs, rowNum) -> new AlarmRow(
            rs.getLong("id"),
            rs.getLong("tank_id"),
            AlarmLevel.valueOf(rs.getString("level")),
            rs.getString("metric"),
            rs.getString("message"),
            AlarmStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());

    private static final RowMapper<DisposalOrderRow> ORDER_MAPPER = (rs, rowNum) -> new DisposalOrderRow(
            rs.getLong("id"),
            rs.getLong("alarm_id"),
            rs.getLong("tank_id"),
            DisposalType.valueOf(rs.getString("type")),
            DisposalStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("issued_at").toInstant(),
            rs.getTimestamp("confirm_deadline").toInstant(),
            rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(),
            rs.getTimestamp("escalated_at") == null ? null : rs.getTimestamp("escalated_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public WaterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TankRow> findTank(long tankId) {
        List<TankRow> rows = jdbcTemplate.query("SELECT * FROM tank WHERE id = ?", TANK_MAPPER, tankId);
        return rows.stream().findFirst();
    }

    /**
     * 以行锁读取展示池，须在事务内调用。
     */
    public Optional<TankRow> lockTank(long tankId) {
        List<TankRow> rows = jdbcTemplate.query("SELECT * FROM tank WHERE id = ? FOR UPDATE", TANK_MAPPER, tankId);
        return rows.stream().findFirst();
    }

    public List<TankRow> findAllTanks() {
        return jdbcTemplate.query("SELECT * FROM tank ORDER BY id", TANK_MAPPER);
    }

    /**
     * 更新展示池状态字段（状态机、计数器与传感器状态）。
     */
    public void updateTankState(TankRow tank) {
        jdbcTemplate.update(
                "UPDATE tank SET status = ?, display_suspended = ?, auto_disposal_suspended = ?,"
                        + " data_missing = ?, consecutive_violations = ?, sensor_status = ?, sensor_last_seen_at = ?"
                        + " WHERE id = ?",
                tank.status().name(),
                tank.displaySuspended() ? 1 : 0,
                tank.autoDisposalSuspended() ? 1 : 0,
                tank.dataMissing() ? 1 : 0,
                tank.consecutiveViolations(),
                tank.sensorStatus().name(),
                Timestamp.from(tank.sensorLastSeenAt()),
                tank.id());
    }

    public long insertSample(long tankId, double dissolvedOxygen, double ammoniaNitrogen, double temperature,
                             boolean violating, Instant sampledAt, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO water_sample(tank_id, dissolved_oxygen, ammonia_nitrogen, temperature,"
                            + " violating, sampled_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tankId);
            ps.setDouble(2, dissolvedOxygen);
            ps.setDouble(3, ammoniaNitrogen);
            ps.setDouble(4, temperature);
            ps.setInt(5, violating ? 1 : 0);
            ps.setTimestamp(6, Timestamp.from(sampledAt));
            ps.setTimestamp(7, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long insertAlarm(long tankId, AlarmLevel level, String metric, String message, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO alarm(tank_id, level, metric, message, status, created_at)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tankId);
            ps.setString(2, level.name());
            ps.setString(3, metric);
            ps.setString(4, message);
            ps.setTimestamp(5, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 解除展示池全部生效中告警，返回解除条数。
     */
    public int resolveActiveAlarms(long tankId, Instant resolvedAt) {
        return jdbcTemplate.update(
                "UPDATE alarm SET status = 'RESOLVED', resolved_at = ? WHERE tank_id = ? AND status = 'ACTIVE'",
                Timestamp.from(resolvedAt), tankId);
    }

    public List<AlarmRow> findActiveAlarms(long tankId) {
        return jdbcTemplate.query(
                "SELECT * FROM alarm WHERE tank_id = ? AND status = 'ACTIVE' ORDER BY id", ALARM_MAPPER, tankId);
    }

    /**
     * 查询与指定时间段有交集的告警（告警生效区间与 [start, end) 相交）。
     */
    public List<AlarmRow> findAlarmsOverlapping(long tankId, Instant start, Instant end) {
        return jdbcTemplate.query(
                "SELECT * FROM alarm WHERE tank_id = ? AND created_at < ?"
                        + " AND (resolved_at IS NULL OR resolved_at > ?) ORDER BY id",
                ALARM_MAPPER, tankId, Timestamp.from(end), Timestamp.from(start));
    }

    public long insertDisposalOrder(long alarmId, long tankId, DisposalType type,
                                    Instant issuedAt, Instant confirmDeadline) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO disposal_order(alarm_id, tank_id, type, status, issued_at, confirm_deadline)"
                            + " VALUES (?, ?, ?, 'PENDING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, alarmId);
            ps.setLong(2, tankId);
            ps.setString(3, type.name());
            ps.setTimestamp(4, Timestamp.from(issuedAt));
            ps.setTimestamp(5, Timestamp.from(confirmDeadline));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<DisposalOrderRow> findOrder(long orderId) {
        List<DisposalOrderRow> rows = jdbcTemplate.query(
                "SELECT * FROM disposal_order WHERE id = ?", ORDER_MAPPER, orderId);
        return rows.stream().findFirst();
    }

    /**
     * 以行锁读取处置指令，须在事务内调用。
     */
    public Optional<DisposalOrderRow> lockOrder(long orderId) {
        List<DisposalOrderRow> rows = jdbcTemplate.query(
                "SELECT * FROM disposal_order WHERE id = ? FOR UPDATE", ORDER_MAPPER, orderId);
        return rows.stream().findFirst();
    }

    public void markOrderConfirmed(long orderId, Instant confirmedAt) {
        jdbcTemplate.update(
                "UPDATE disposal_order SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?",
                Timestamp.from(confirmedAt), orderId);
    }

    public void markOrderEscalated(long orderId, Instant escalatedAt) {
        jdbcTemplate.update(
                "UPDATE disposal_order SET status = 'ESCALATED', escalated_at = ? WHERE id = ?",
                Timestamp.from(escalatedAt), orderId);
    }

    public boolean hasPendingOrder(long tankId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal_order WHERE tank_id = ? AND status = 'PENDING'",
                Integer.class, tankId);
        return count != null && count > 0;
    }

    /**
     * 查询恰满或超过确认时限仍待确认的指令（confirm_deadline <= now）。
     */
    public List<DisposalOrderRow> findOverduePendingOrders(Instant now) {
        return jdbcTemplate.query(
                "SELECT * FROM disposal_order WHERE status = 'PENDING' AND confirm_deadline <= ? ORDER BY id",
                ORDER_MAPPER, Timestamp.from(now));
    }

    /**
     * 查询恰满或超过失联时长仍在在线状态的展示池（last_seen + 失联时长 <= now）。
     */
    public List<TankRow> findSensorLostCandidates(Instant cutoff) {
        return jdbcTemplate.query(
                "SELECT * FROM tank WHERE sensor_status = 'ONLINE' AND sensor_last_seen_at <= ? ORDER BY id",
                TANK_MAPPER, Timestamp.from(cutoff));
    }

    public long insertRetest(long tankId, double dissolvedOxygen, double ammoniaNitrogen, double temperature,
                             boolean passed, Instant recordedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO retest_record(tank_id, dissolved_oxygen, ammonia_nitrogen, temperature,"
                            + " passed, recorded_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tankId);
            ps.setDouble(2, dissolvedOxygen);
            ps.setDouble(3, ammoniaNitrogen);
            ps.setDouble(4, temperature);
            ps.setInt(5, passed ? 1 : 0);
            ps.setTimestamp(6, Timestamp.from(recordedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long insertExhibit(long tankId, String exhibitName, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO exhibit_registration(tank_id, exhibit_name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tankId);
            ps.setString(2, exhibitName);
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<DisposalOrderRow> findOrdersByAlarmId(long alarmId) {
        return jdbcTemplate.query(
                "SELECT * FROM disposal_order WHERE alarm_id = ? ORDER BY id", ORDER_MAPPER, alarmId);
    }
}
