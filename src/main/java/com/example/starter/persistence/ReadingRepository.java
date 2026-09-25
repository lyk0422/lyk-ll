package com.example.starter.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 计量读数与迟到读数差异登记数据访问。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<MeterReadingPO> ROW_MAPPER = (rs, n) -> new MeterReadingPO(
            rs.getLong("id"),
            rs.getString("station_code"),
            rs.getString("meter_id"),
            rs.getTimestamp("reading_time").toInstant(),
            rs.getBigDecimal("meter_index"),
            rs.getBigDecimal("heating_value"),
            rs.getTimestamp("received_at").toInstant(),
            rs.getBoolean("is_late"));

    /**
     * 插入读数，按“交接站＋表底时刻”去重；重复键返回已有读数且 created=false（乱序归位，不覆盖）。
     */
    public ReadingUpsertResult insertIfAbsent(String stationCode, String meterId,
            Instant readingTime, BigDecimal meterIndex, BigDecimal heatingValue, Instant now) {
        var existing = findByKey(stationCode, readingTime);
        if (existing.isPresent()) {
            return new ReadingUpsertResult(existing.get(), false);
        }
        try {
            jdbc.update("""
                    INSERT INTO meter_reading
                        (station_code, meter_id, reading_time, meter_index, heating_value,
                         received_at, is_late, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, FALSE, ?)
                    """, stationCode, meterId, Timestamp.from(readingTime), meterIndex,
                    heatingValue, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            return new ReadingUpsertResult(findByKey(stationCode, readingTime).orElseThrow(),
                    false);
        }
        return new ReadingUpsertResult(findByKey(stationCode, readingTime).orElseThrow(), true);
    }

    /** 读数插入结果：reading 为落库后读数，created 表示是否本次新建。 */
    public record ReadingUpsertResult(MeterReadingPO reading, boolean created) {
    }

    /** 按业务唯一键查询读数。 */
    public Optional<MeterReadingPO> findByKey(String stationCode, Instant readingTime) {
        return jdbc.query("""
                SELECT * FROM meter_reading
                WHERE station_code = ? AND reading_time = ?
                """, ROW_MAPPER, stationCode, Timestamp.from(readingTime)).stream().findFirst();
    }

    /**
     * 查询某站某气量日 [day 00:00, 次日 00:00) UTC 内全部读数，按表底时刻升序归位。
     */
    public List<MeterReadingPO> findByGasDay(String stationCode, LocalDate gasDay) {
        LocalDate next = gasDay.plusDays(1);
        return jdbc.query("""
                SELECT * FROM meter_reading
                WHERE station_code = ?
                  AND reading_time >= ? AND reading_time < ?
                ORDER BY reading_time ASC, id ASC
                """, ROW_MAPPER, stationCode,
                Timestamp.from(gasDay.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
                Timestamp.from(next.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
    }

    /** 将指定读数标记为迟到读数（其所属气量日凭证已生成）。 */
    public int markLateById(Long id) {
        return jdbc.update("UPDATE meter_reading SET is_late = TRUE WHERE id = ?", id);
    }

    /** 登记迟到读数差异记录，不改已生成凭证。 */
    public void insertDifference(String voucherNo, Long readingId, String stationCode,
            LocalDate gasDay, MeterReadingPO reading, String note, Instant now) {
        jdbc.update("""
                INSERT INTO reading_difference
                    (voucher_no, reading_id, station_code, gas_day, reading_time, meter_index,
                     heating_value, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, voucherNo, readingId, stationCode, Date.valueOf(gasDay),
                Timestamp.from(reading.readingTime()), reading.meterIndex(),
                reading.heatingValue(), note, Timestamp.from(now));
    }

    /** 按主键查询读数。 */
    public Optional<MeterReadingPO> findById(Long id) {
        return jdbc.query("SELECT * FROM meter_reading WHERE id = ?", ROW_MAPPER, id).stream()
                .findFirst();
    }
}
