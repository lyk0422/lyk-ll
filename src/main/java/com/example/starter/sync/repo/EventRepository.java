package com.example.starter.sync.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.sync.SensorType;

/**
 * 同步事件仓储：所有状态变更按全局序号追加，供同 JVM 内重放重建融合帧。
 */
@Repository
public class EventRepository {

    private static final RowMapper<SyncEventRow> ROW_MAPPER = (rs, n) -> new SyncEventRow(
            rs.getLong("seq"),
            rs.getString("event_type"),
            rs.getString("sensor_type") == null ? null : SensorType.valueOf(rs.getString("sensor_type")),
            rs.getString("message_id"),
            rs.getObject("target_ts") == null ? null : rs.getLong("target_ts"),
            rs.getObject("raw_ts") == null ? null : rs.getLong("raw_ts"),
            rs.getObject("drift_version") == null ? null : rs.getInt("drift_version"),
            rs.getObject("offset_ms") == null ? null : rs.getLong("offset_ms"),
            rs.getObject("effective_from") == null ? null : rs.getLong("effective_from"),
            rs.getString("reason"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EventRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 追加一条事件，返回全局事件序号。
     */
    public long append(String eventType, SensorType sensorType, String messageId, Long targetTs,
                       Long rawTs, Integer driftVersion, Long offsetMs, Long effectiveFrom, String reason) {
        String sql = "INSERT INTO sync_event (event_type, sensor_type, message_id, target_ts, raw_ts,"
                + " drift_version, offset_ms, effective_from, reason, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keys = new GeneratedKeyHolder();
        long createdAt = clock.millis();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, eventType);
            ps.setString(2, sensorType == null ? null : sensorType.name());
            ps.setString(3, messageId);
            setLongOrNull(ps, 4, targetTs);
            setLongOrNull(ps, 5, rawTs);
            if (driftVersion == null) {
                ps.setObject(6, null);
            } else {
                ps.setInt(6, driftVersion);
            }
            setLongOrNull(ps, 7, offsetMs);
            setLongOrNull(ps, 8, effectiveFrom);
            ps.setString(9, reason);
            ps.setLong(10, createdAt);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? -1L : key.longValue();
    }

    private static void setLongOrNull(PreparedStatement ps, int idx, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setObject(idx, null);
        } else {
            ps.setLong(idx, value);
        }
    }

    /**
     * 按全局序号升序读取全部事件，用于重放。
     */
    public List<SyncEventRow> findAllOrdered() {
        return jdbc.query("SELECT * FROM sync_event ORDER BY seq", ROW_MAPPER);
    }
}
