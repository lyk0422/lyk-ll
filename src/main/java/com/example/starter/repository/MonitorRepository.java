package com.example.starter.repository;

import com.example.starter.domain.MonitorZone;
import com.example.starter.domain.RadarReading;
import com.example.starter.domain.ZonePerson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 监测数据仓储：测区、人员定位、雷达读数、数据事件。
 */
@Repository
public class MonitorRepository {

    private static final RowMapper<MonitorZone> ZONE_MAPPER = (rs, rowNum) -> {
        MonitorZone z = new MonitorZone();
        z.setId(rs.getLong("id"));
        z.setZoneCode(rs.getString("zone_code"));
        z.setZoneName(rs.getString("zone_name"));
        z.setWarnHourlyRate(rs.getBigDecimal("warn_hourly_rate"));
        z.setAlarmDailyRate(rs.getBigDecimal("alarm_daily_rate"));
        z.setSafetyHourlyRate(rs.getBigDecimal("safety_hourly_rate"));
        z.setProductionStatus(rs.getString("production_status"));
        z.setOperationLocked(rs.getInt("operation_locked") == 1);
        Timestamp createdAt = rs.getTimestamp("created_at");
        z.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        z.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
        return z;
    };

    private static final RowMapper<RadarReading> READING_MAPPER = (rs, rowNum) -> {
        RadarReading r = new RadarReading();
        r.setId(rs.getLong("id"));
        r.setZoneCode(rs.getString("zone_code"));
        Timestamp readingTime = rs.getTimestamp("reading_time");
        r.setReadingTime(readingTime == null ? null : readingTime.toLocalDateTime());
        r.setDisplacementMm(rs.getBigDecimal("displacement_mm"));
        r.setLateFlag(rs.getInt("late_flag") == 1);
        Timestamp receivedAt = rs.getTimestamp("received_at");
        r.setReceivedAt(receivedAt == null ? null : receivedAt.toLocalDateTime());
        return r;
    };

    private static final RowMapper<ZonePerson> PERSON_MAPPER = (rs, rowNum) -> {
        ZonePerson p = new ZonePerson();
        p.setId(rs.getLong("id"));
        p.setZoneCode(rs.getString("zone_code"));
        p.setPersonId(rs.getString("person_id"));
        p.setOnSite(rs.getInt("on_site") == 1);
        Timestamp locatedAt = rs.getTimestamp("located_at");
        p.setLocatedAt(locatedAt == null ? null : locatedAt.toLocalDateTime());
        return p;
    };

    private final JdbcTemplate jdbcTemplate;

    public MonitorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertZone(MonitorZone zone) {
        jdbcTemplate.update(
                "INSERT INTO monitor_zone (zone_code, zone_name, warn_hourly_rate, alarm_daily_rate, "
                        + "safety_hourly_rate, production_status, operation_locked) VALUES (?, ?, ?, ?, ?, ?, ?)",
                zone.getZoneCode(), zone.getZoneName(), zone.getWarnHourlyRate(), zone.getAlarmDailyRate(),
                zone.getSafetyHourlyRate(), zone.getProductionStatus(), zone.isOperationLocked() ? 1 : 0);
    }

    public MonitorZone findZone(String zoneCode) {
        List<MonitorZone> list = jdbcTemplate.query(
                "SELECT id, zone_code, zone_name, warn_hourly_rate, alarm_daily_rate, safety_hourly_rate, "
                        + "production_status, operation_locked, created_at, updated_at "
                        + "FROM monitor_zone WHERE zone_code = ?", ZONE_MAPPER, zoneCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<MonitorZone> findAllZones() {
        return jdbcTemplate.query(
                "SELECT id, zone_code, zone_name, warn_hourly_rate, alarm_daily_rate, safety_hourly_rate, "
                        + "production_status, operation_locked, created_at, updated_at FROM monitor_zone",
                ZONE_MAPPER);
    }

    public void updateZoneStatus(String zoneCode, String productionStatus, boolean operationLocked) {
        jdbcTemplate.update(
                "UPDATE monitor_zone SET production_status = ?, operation_locked = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(3) WHERE zone_code = ?",
                productionStatus, operationLocked ? 1 : 0, zoneCode);
    }

    /**
     * 红色预警立即停产：仅 ACTIVE/RESUMED 可翻转为 STOPPED，并发停产只有一个成功（唯一终态保护）。
     *
     * @return 受影响行数；0 表示当前已是停产状态，终态冲突
     */
    public int updateZoneStatusForStop(String zoneCode) {
        return jdbcTemplate.update(
                "UPDATE monitor_zone SET production_status = 'STOPPED', "
                        + "updated_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE zone_code = ? AND production_status IN ('ACTIVE', 'RESUMED')",
                zoneCode);
    }

    /**
     * 作业封锁（与撤人确认同一事务调用）。
     */
    public void lockOperation(String zoneCode) {
        jdbcTemplate.update(
                "UPDATE monitor_zone SET operation_locked = 1, updated_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE zone_code = ?", zoneCode);
    }

    /**
     * 复工批准：仅“已停产 + 封锁中”可翻转为 RESUMED 并解除封锁，
     * 条件更新保证停产/复工唯一终态，并发双确认最多一个成功。
     *
     * @return 受影响行数；0 表示状态已被并发改变
     */
    public int updateZoneStatusForResume(String zoneCode) {
        return jdbcTemplate.update(
                "UPDATE monitor_zone SET production_status = 'RESUMED', operation_locked = 0, "
                        + "updated_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE zone_code = ? AND production_status = 'STOPPED' AND operation_locked = 1",
                zoneCode);
    }

    /**
     * 插入一条读数；“测区＋时刻”唯一冲突时视为重复（去重）。
     *
     * @return 是否新插入
     */
    public boolean insertReadingIgnoreDuplicate(String zoneCode, LocalDateTime readingTime,
                                                java.math.BigDecimal displacementMm, boolean late) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO radar_reading (zone_code, reading_time, displacement_mm, late_flag) "
                            + "VALUES (?, ?, ?, ?)",
                    zoneCode, Timestamp.valueOf(readingTime), displacementMm, late ? 1 : 0);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return false;
        }
    }

    /**
     * 查全部读数（含迟到标记），调用方按时刻归位复算。
     */
    public List<RadarReading> findReadings(String zoneCode) {
        return jdbcTemplate.query(
                "SELECT id, zone_code, reading_time, displacement_mm, late_flag, received_at "
                        + "FROM radar_reading WHERE zone_code = ? ORDER BY reading_time",
                READING_MAPPER, zoneCode);
    }

    public List<RadarReading> findReadingsBetween(String zoneCode, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query(
                "SELECT id, zone_code, reading_time, displacement_mm, late_flag, received_at "
                        + "FROM radar_reading WHERE zone_code = ? AND reading_time >= ? AND reading_time <= ? "
                        + "ORDER BY reading_time",
                READING_MAPPER, zoneCode, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    public void upsertPersonLocation(String zoneCode, String personId, boolean onSite, LocalDateTime locatedAt) {
        // H2 MODE=MySQL 下使用 MERGE ... KEY 实现“测区+人员”唯一 upsert，保留最近一条定位
        jdbcTemplate.update(
                "MERGE INTO zone_person (zone_code, person_id, on_site, located_at) KEY(zone_code, person_id) "
                        + "VALUES (?, ?, ?, ?)",
                zoneCode, personId, onSite ? 1 : 0, Timestamp.valueOf(locatedAt));
    }

    /**
     * 预警触发时刻在册（on_site=1 且定位时刻不晚于触发时刻）人员。
     * upsert 保证“测区+人员”仅保留最近一条定位。
     */
    public List<ZonePerson> findOnSitePersonsAt(String zoneCode, LocalDateTime atTime) {
        return jdbcTemplate.query(
                "SELECT id, zone_code, person_id, on_site, located_at FROM zone_person "
                        + "WHERE zone_code = ? AND on_site = 1 AND located_at <= ?",
                PERSON_MAPPER, zoneCode, Timestamp.valueOf(atTime));
    }

    public void insertEvent(String zoneCode, String eventType, LocalDateTime readingTime, String payload) {
        jdbcTemplate.update(
                "INSERT INTO data_event (zone_code, event_type, reading_time, payload) VALUES (?, ?, ?, ?)",
                zoneCode, eventType, readingTime == null ? null : Timestamp.valueOf(readingTime), payload);
    }

    public List<String> findEventTypes(String zoneCode, String eventType) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM data_event WHERE zone_code = ? AND event_type = ?",
                String.class, zoneCode, eventType);
    }

    public int countReadings(String zoneCode) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM radar_reading WHERE zone_code = ?", Integer.class, zoneCode);
        return c == null ? 0 : c;
    }

    public int countLateReadings(String zoneCode) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM radar_reading WHERE zone_code = ? AND late_flag = 1",
                Integer.class, zoneCode);
        return c == null ? 0 : c;
    }

    /** 仅用于测试：清空全部业务表。 */
    public void deleteAllForTest() {
        jdbcTemplate.update("DELETE FROM data_event");
        jdbcTemplate.update("DELETE FROM resume_assessment");
        jdbcTemplate.update("DELETE FROM evacuation_person");
        jdbcTemplate.update("DELETE FROM disposal_chain");
        jdbcTemplate.update("DELETE FROM zone_person");
        jdbcTemplate.update("DELETE FROM radar_reading");
        jdbcTemplate.update("DELETE FROM monitor_zone");
    }
}
