package com.example.starter.repository;

import com.example.starter.domain.CapacityReading;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 容量读数访问：按“基站＋抄表时刻”去重；乱序上报按抄表时刻归位（最新值取抄表时刻最大者）。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入抄表记录；同基站同抄表时刻重复上报返回 false，不重复入库。
     */
    public boolean insertIfAbsent(long stationId, Instant readAt, BigDecimal remainingWh,
                                  int estimatedMinutes, Instant createdAt) {
        try {
            int rows = jdbc.update("""
                            INSERT INTO capacity_reading
                                (station_id, read_at, remaining_wh, estimated_minutes, created_at)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    stationId, Timestamp.from(readAt), remainingWh, estimatedMinutes,
                    Timestamp.from(createdAt));
            return rows == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<CapacityReading> find(long stationId, Instant readAt) {
        return jdbc.query("SELECT * FROM capacity_reading WHERE station_id = ? AND read_at = ?",
                CapacityReading.rowMapper(), stationId, Timestamp.from(readAt))
                .stream().findFirst();
    }

    /** 按抄表时刻归位后的最新一条读数（与入库先后无关）。 */
    public Optional<CapacityReading> findLatestByReadAt(long stationId) {
        return jdbc.query("""
                        SELECT * FROM capacity_reading
                         WHERE station_id = ?
                         ORDER BY read_at DESC
                         LIMIT 1
                        """,
                CapacityReading.rowMapper(), stationId).stream().findFirst();
    }

    /** 全部读数，按抄表时刻升序归位返回。 */
    public List<CapacityReading> findOrderedByReadAt(long stationId) {
        List<CapacityReading> readings = jdbc.query(
                "SELECT * FROM capacity_reading WHERE station_id = ? ORDER BY read_at",
                CapacityReading.rowMapper(), stationId);
        readings.sort(Comparator.comparing(CapacityReading::readAt));
        return readings;
    }
}
