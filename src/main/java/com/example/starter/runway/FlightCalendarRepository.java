package com.example.starter.runway;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 航班日历数据访问：flight_calendar 表。日历行兼作跑道级并发锁（SELECT ... FOR UPDATE），
 * 使计划发布/开始与紧急接报在同一跑道上串行，跨跑道互不阻塞。
 */
@Repository
public class FlightCalendarRepository {

    private static final RowMapper<FlightCalendar> MAPPER = (rs, rowNum) -> new FlightCalendar(
            rs.getString("runway_id"),
            rs.getInt("version"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public FlightCalendarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FlightCalendar> findByRunwayId(String runwayId) {
        List<FlightCalendar> rows = jdbc.query(
                "SELECT * FROM flight_calendar WHERE runway_id = ?", MAPPER, runwayId);
        return rows.stream().findFirst();
    }

    /**
     * 锁定并返回跑道日历行；不存在时以版本1创建。并发首建冲突时重试锁定读取。
     */
    public FlightCalendar getOrCreateForUpdate(String runwayId, Instant now) {
        Optional<FlightCalendar> locked = lockByRunwayId(runwayId);
        if (locked.isPresent()) {
            return locked.get();
        }
        try {
            jdbc.update("INSERT INTO flight_calendar (runway_id, version, updated_at) VALUES (?,?,?)",
                    runwayId, 1, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // 并发首建：另一事务已插入，改为锁定读取
        }
        return lockByRunwayId(runwayId).orElseThrow();
    }

    /**
     * 版本加一并返回新版本号；调用方须已持有行锁。
     */
    public int incrementVersion(String runwayId, Instant updatedAt) {
        jdbc.update("UPDATE flight_calendar SET version = version + 1, updated_at = ? WHERE runway_id = ?",
                Timestamp.from(updatedAt), runwayId);
        return findByRunwayId(runwayId).orElseThrow().version();
    }

    private Optional<FlightCalendar> lockByRunwayId(String runwayId) {
        List<FlightCalendar> rows = jdbc.query(
                "SELECT * FROM flight_calendar WHERE runway_id = ? FOR UPDATE", MAPPER, runwayId);
        return rows.stream().findFirst();
    }
}
