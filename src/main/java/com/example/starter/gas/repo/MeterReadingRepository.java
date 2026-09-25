package com.example.starter.gas.repo;

import com.example.starter.gas.domain.MeterReadingRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 计量读数数据访问：按“交接站＋表底时刻”去重。
 */
@Repository
public class MeterReadingRepository {

    private static final RowMapper<MeterReadingRow> ROW_MAPPER = (rs, rowNum) -> new MeterReadingRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("station_code"),
            rs.getTimestamp("meter_time").toInstant(),
            rs.getDate("gas_date").toLocalDate(),
            rs.getBigDecimal("volume"),
            rs.getBigDecimal("heating_value"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public MeterReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按站与表底时刻查找读数（去重判断）。 */
    public MeterReadingRow findByStationAndMeterTime(String stationCode, Instant meterTime) {
        List<MeterReadingRow> rows = jdbc.query(
                "SELECT * FROM meter_reading WHERE station_code = ? AND meter_time = ?",
                ROW_MAPPER, stationCode, Timestamp.from(meterTime));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 插入读数，返回生成主键；createdAt 为读数确认入库时刻（UTC）。 */
    public long insert(String requestId, String stationCode, Instant meterTime,
                       LocalDate gasDate, BigDecimal volume, BigDecimal heatingValue,
                       Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO meter_reading (request_id, station_code, meter_time, gas_date, "
                            + "volume, heating_value, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setString(2, stationCode);
            ps.setTimestamp(3, Timestamp.from(meterTime));
            ps.setDate(4, java.sql.Date.valueOf(gasDate));
            ps.setBigDecimal(5, volume);
            ps.setBigDecimal(6, heatingValue);
            ps.setTimestamp(7, Timestamp.from(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询。 */
    public MeterReadingRow findById(long id) {
        List<MeterReadingRow> rows = jdbc.query(
                "SELECT * FROM meter_reading WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查询某气量日“24:00（UTC）前已确认入库”的读数：
     * 表底时刻落在 [当日 00:00, 次日 00:00)，入库时刻不晚于次日 00:00；按表底时刻升序乱序归位。
     */
    public List<MeterReadingRow> findConfirmedForDay(String stationCode, LocalDate gasDate) {
        Timestamp dayStart = Timestamp.from(gasDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        Timestamp dayEnd = Timestamp.from(gasDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        return jdbc.query(
                "SELECT * FROM meter_reading WHERE station_code = ? AND meter_time >= ? AND meter_time < ? "
                        + "AND created_at <= ? ORDER BY meter_time ASC, id ASC",
                ROW_MAPPER, stationCode, dayStart, dayEnd, dayEnd);
    }

    /**
     * 查询迟到读数：表底时刻归属该气量日，但在气量日 24:00（UTC）之后才入库（按表底时刻升序）。
     */
    public List<MeterReadingRow> findLateReadings(String stationCode, LocalDate gasDate) {
        Timestamp dayStart = Timestamp.from(gasDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        Timestamp dayEnd = Timestamp.from(gasDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        return jdbc.query(
                "SELECT * FROM meter_reading WHERE station_code = ? AND meter_time >= ? AND meter_time < ? "
                        + "AND created_at > ? ORDER BY meter_time ASC, id ASC",
                ROW_MAPPER, stationCode, dayStart, dayEnd, dayEnd);
    }
}
