package com.example.starter.fishery.db;

import com.example.starter.fishery.db.support.GeneratedKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 渔捞日志链访问；按“船＋UTC 日期＋渔区”唯一去重，日志链按作业时刻排序。
 */
@Repository
public class LogbookRepository {

    private final JdbcTemplate jdbc;

    public LogbookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LogbookRow mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new LogbookRow(
                rs.getLong("id"),
                rs.getString("trip_code"),
                rs.getString("vessel_code"),
                rs.getString("zone_code"),
                rs.getObject("log_date", LocalDate.class),
                rs.getTimestamp("fishing_at").toInstant(),
                rs.getBigDecimal("total_kg"),
                rs.getBoolean("bycatch_flag"),
                rs.getBoolean("late"),
                rs.getInt("seq_no"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** 按“船＋UTC 日期＋渔区”查既有日志（去重用）。 */
    public Optional<LogbookRow> findByVesselDateZone(String vesselCode, LocalDate logDate, String zoneCode) {
        List<LogbookRow> rows = jdbc.query(
                "SELECT id, trip_code, vessel_code, zone_code, log_date, fishing_at, total_kg, "
                        + "bycatch_flag, late, seq_no, created_at "
                        + "FROM logbook WHERE vessel_code = ? AND log_date = ? AND zone_code = ?",
                LogbookRepository::mapRow, vesselCode, logDate, zoneCode);
        return rows.stream().findFirst();
    }

    /** 新增日志，{@code seqNo} 由调用方按作业时刻归位后给定。 */
    public long insert(LogbookRow row) {
        return GeneratedKeys.insertAndReturnKey(
                jdbc,
                "INSERT INTO logbook(trip_code, vessel_code, zone_code, log_date, fishing_at, "
                        + "total_kg, bycatch_flag, late, seq_no, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.tripCode(), row.vesselCode(), row.zoneCode(), row.logDate(),
                Timestamp.from(row.fishingAt()), row.totalKg(), row.bycatchFlag(), row.late(),
                row.seqNo(), Timestamp.from(row.createdAt())).longValue();
    }

    /** 写日志分鱼种行。 */
    public void insertLine(long logbookId, String speciesCode, BigDecimal weightKg) {
        jdbc.update("INSERT INTO logbook_line(logbook_id, species_code, weight_kg) VALUES (?, ?, ?)",
                logbookId, speciesCode, weightKg);
    }

    /** 查航次全部日志，按作业时刻排序（跨日/乱序归位后的日志链）。 */
    public List<LogbookRow> findByTrip(String tripCode) {
        return jdbc.query(
                "SELECT id, trip_code, vessel_code, zone_code, log_date, fishing_at, total_kg, "
                        + "bycatch_flag, late, seq_no, created_at "
                        + "FROM logbook WHERE trip_code = ? ORDER BY fishing_at, id",
                LogbookRepository::mapRow, tripCode);
    }

    /** 乱序提交后按作业时刻重排航次内日志链序号（1 起）。 */
    public void renumberSequence(String tripCode) {
        List<Long> orderedIds = jdbc.queryForList(
                "SELECT id FROM logbook WHERE trip_code = ? ORDER BY fishing_at, id",
                Long.class, tripCode);
        int seq = 1;
        for (Long id : orderedIds) {
            jdbc.update("UPDATE logbook SET seq_no = ? WHERE id = ?", seq++, id);
        }
    }

    /** 按 id 查日志。 */
    public Optional<LogbookRow> findById(long id) {
        List<LogbookRow> rows = jdbc.query(
                "SELECT id, trip_code, vessel_code, zone_code, log_date, fishing_at, total_kg, "
                        + "bycatch_flag, late, seq_no, created_at FROM logbook WHERE id = ?",
                LogbookRepository::mapRow, id);
        return rows.stream().findFirst();
    }

    /** 查日志分鱼种行。 */
    public List<TripRepository.WeightLine> findLines(long logbookId) {
        return jdbc.query("SELECT species_code, weight_kg FROM logbook_line WHERE logbook_id = ? "
                        + "ORDER BY species_code",
                (rs, n) -> new TripRepository.WeightLine(rs.getString("species_code"),
                        rs.getBigDecimal("weight_kg")),
                logbookId);
    }

    /**
     * 汇总航次有效日志（排除结案后迟到日志）分鱼种累计量，作为日志口径。
     */
    public List<TripRepository.WeightLine> sumEffectiveLines(String tripCode) {
        return jdbc.query(
                "SELECT ll.species_code AS species_code, SUM(ll.weight_kg) AS weight_kg "
                        + "FROM logbook_line ll JOIN logbook l ON l.id = ll.logbook_id "
                        + "WHERE l.trip_code = ? AND l.late = FALSE "
                        + "GROUP BY ll.species_code ORDER BY ll.species_code",
                (rs, n) -> new TripRepository.WeightLine(rs.getString("species_code"),
                        rs.getBigDecimal("weight_kg")),
                tripCode);
    }

    /** 登记结案后迟到日志事件（不参与核销，只入审查队列）。 */
    public void insertLateEvent(String tripCode, String vesselCode, String zoneCode,
                                LocalDate logDate, Instant fishingAt, String payloadJson, Instant now) {
        jdbc.update("INSERT INTO late_log_event(trip_code, vessel_code, zone_code, log_date, "
                        + "fishing_at, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                tripCode, vesselCode, zoneCode, java.sql.Date.valueOf(logDate),
                Timestamp.from(fishingAt), payloadJson, Timestamp.from(now));
    }

    /** 日志链连续性校验：序号 1..N 与作业时刻升序一致（复算用）。 */
    public boolean chainIsContiguous(String tripCode) {
        List<LogbookRow> rows = findByTrip(tripCode);
        int expected = 1;
        Instant previous = null;
        for (LogbookRow row : rows) {
            if (row.seqNo() != expected) {
                return false;
            }
            if (previous != null && row.fishingAt().isBefore(previous)) {
                return false;
            }
            previous = row.fishingAt();
            expected++;
        }
        return true;
    }
}
