package com.example.starter.battery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 电池事件链数据访问。
 */
@Repository
public class BatteryEventRepository {

    private static final RowMapper<BatteryEvent> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public BatteryEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static BatteryEvent map(ResultSet rs) throws SQLException {
        return new BatteryEvent(
                rs.getLong("id"),
                rs.getString("battery_code"),
                rs.getLong("seq"),
                EventType.valueOf(rs.getString("event_type")),
                rs.getString("payload"),
                rs.getString("prev_hash"),
                rs.getString("hash"),
                rs.getLong("created_at"));
    }

    /** 追加事件，seq 由调用方按链尾序号 +1 计算。 */
    public void append(BatteryEvent event) {
        jdbc.update(
                "INSERT INTO battery_event (battery_code, seq, event_type, payload, prev_hash, hash, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                event.batteryCode(), event.seq(), event.eventType().name(), event.payload(),
                event.prevHash(), event.hash(), event.createdAt());
    }

    /** 查询某电池最新事件。 */
    public Optional<BatteryEvent> findLatest(String batteryCode) {
        return jdbc.query(
                        "SELECT * FROM battery_event WHERE battery_code = ? ORDER BY seq DESC LIMIT 1",
                        MAPPER, batteryCode)
                .stream().findFirst();
    }

    /** 按序号升序查询完整事件链。 */
    public List<BatteryEvent> findChain(String batteryCode) {
        return jdbc.query(
                "SELECT * FROM battery_event WHERE battery_code = ? ORDER BY seq ASC",
                MAPPER, batteryCode);
    }
}
