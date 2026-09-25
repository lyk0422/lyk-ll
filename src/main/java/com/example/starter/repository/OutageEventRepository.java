package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.model.OutageEvent;
import com.example.starter.model.OutageStatus;

/**
 * 停电事件数据访问；按“基站＋停电起始时刻”去重，同站事件期内告警合并。
 */
@Repository
public class OutageEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutageEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新建停电事件，返回自增主键；唯一约束 (station_code, outage_start_time) 由数据库兜底。
     */
    public long insert(String stationCode, Instant outageStartTime, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO outage_event (station_code, outage_start_time, status, resolved_at, created_at)"
                        + " VALUES (?,?,?,?,?)",
                stationCode, StationRepository.toOffsetDateTime(outageStartTime),
                OutageStatus.ACTIVE.name(), null, StationRepository.toOffsetDateTime(now));
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM outage_event WHERE station_code = ?", Long.class, stationCode);
        return id == null ? -1L : id;
    }

    /**
     * 查询基站当前停电中的事件（事件期内重复告警合并依据）。
     */
    public Optional<OutageEvent> findActiveByStation(String stationCode) {
        return jdbcTemplate.query(
                        "SELECT * FROM outage_event WHERE station_code = ? AND status = ?"
                                + " ORDER BY id DESC LIMIT 1",
                        this::map, stationCode, OutageStatus.ACTIVE.name())
                .stream().findFirst();
    }

    /**
     * 按“基站＋停电起始时刻”查询，用于告警去重。
     */
    public Optional<OutageEvent> findByStationAndStart(String stationCode, Instant outageStartTime) {
        return jdbcTemplate.query(
                        "SELECT * FROM outage_event WHERE station_code = ? AND outage_start_time = ?",
                        this::map, stationCode, StationRepository.toOffsetDateTime(outageStartTime))
                .stream().findFirst();
    }

    /**
     * 市电恢复：事件置为已恢复，返回受影响行数。
     */
    public int resolve(long eventId, Instant recoveredAt) {
        return jdbcTemplate.update(
                "UPDATE outage_event SET status = ?, resolved_at = ? WHERE id = ? AND status = ?",
                OutageStatus.RESOLVED.name(), StationRepository.toOffsetDateTime(recoveredAt),
                eventId, OutageStatus.ACTIVE.name());
    }

    private OutageEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new OutageEvent(rs.getLong("id"), rs.getString("station_code"),
                StationRepository.toInstant(rs, "outage_start_time"),
                OutageStatus.valueOf(rs.getString("status")),
                StationRepository.toInstant(rs, "resolved_at"),
                StationRepository.toInstant(rs, "created_at"));
    }
}
