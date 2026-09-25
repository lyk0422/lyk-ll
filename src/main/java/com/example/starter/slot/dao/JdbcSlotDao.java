package com.example.starter.slot.dao;

import com.example.starter.slot.po.SeasonPo;
import com.example.starter.slot.po.SlotAllocationPo;
import com.example.starter.slot.po.SlotSeriesPo;
import com.example.starter.slot.po.SnapshotAllocationPo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 时刻协调主数据访问 JDBC 实现。
 */
@Repository
public class JdbcSlotDao implements SlotDao {

    private static final RowMapper<SeasonPo> SEASON_MAPPER = (rs, rowNum) ->
            new SeasonPo(
                    rs.getLong("id"),
                    rs.getString("season"),
                    rs.getInt("frozen"),
                    rs.getObject("frozen_at", Long.class),
                    rs.getLong("version"));

    private static final RowMapper<SlotSeriesPo> SERIES_MAPPER = (rs, rowNum) ->
            new SlotSeriesPo(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("season"),
                    rs.getString("airline"),
                    rs.getString("flight_no"),
                    rs.getString("airport"),
                    rs.getString("slot_time"),
                    rs.getString("weeks_csv"),
                    rs.getString("fingerprint"),
                    rs.getString("decision"),
                    rs.getString("status"),
                    rs.getLong("version"),
                    rs.getLong("created_at"));

    private static final RowMapper<SlotAllocationPo> ALLOCATION_MAPPER = (rs, rowNum) ->
            new SlotAllocationPo(
                    rs.getLong("id"),
                    rs.getString("season"),
                    rs.getString("airport"),
                    rs.getString("slot_time"),
                    rs.getInt("week"),
                    rs.getString("airline"),
                    rs.getString("flight_no"),
                    rs.getString("request_id"),
                    rs.getString("basis"),
                    rs.getInt("active"));

    private static final RowMapper<SnapshotAllocationPo> SNAPSHOT_MAPPER = (rs, rowNum) ->
            new SnapshotAllocationPo(
                    rs.getLong("id"),
                    rs.getString("season"),
                    rs.getString("airport"),
                    rs.getString("slot_time"),
                    rs.getInt("week"),
                    rs.getString("airline"),
                    rs.getString("flight_no"),
                    rs.getString("request_id"),
                    rs.getString("basis"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcSlotDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SeasonPo findSeason(String season) {
        List<SeasonPo> rows = jdbcTemplate.query(
                "SELECT id, season, frozen, frozen_at, version FROM slot_season WHERE season = ?",
                SEASON_MAPPER, season);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public SeasonPo findSeasonForUpdate(String season) {
        List<SeasonPo> rows = jdbcTemplate.query(
                "SELECT id, season, frozen, frozen_at, version FROM slot_season WHERE season = ? FOR UPDATE",
                SEASON_MAPPER, season);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public int insertSeasonIfAbsent(String season) {
        try {
            return jdbcTemplate.update(
                    "INSERT INTO slot_season (season, frozen, frozen_at, version, created_at) VALUES (?, 0, NULL, 0, ?)",
                    season, System.currentTimeMillis());
        } catch (DuplicateKeyException e) {
            return 0;
        }
    }

    @Override
    public boolean freezeSeasonIfOpen(String season, Instant now) {
        int updated = jdbcTemplate.update(
                "UPDATE slot_season SET frozen = 1, frozen_at = ?, version = version + 1 WHERE season = ? AND frozen = 0",
                now.toEpochMilli(), season);
        return updated > 0;
    }

    @Override
    public SlotSeriesPo findSeriesByRequestId(String requestId) {
        List<SlotSeriesPo> rows = jdbcTemplate.query(
                """
                SELECT id, request_id, season, airline, flight_no, airport, slot_time, weeks_csv,
                       fingerprint, decision, status, version, created_at
                FROM slot_series WHERE request_id = ?
                """,
                SERIES_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void insertSeries(SlotSeriesPo po) {
        jdbcTemplate.update(
                """
                INSERT INTO slot_series
                    (request_id, season, airline, flight_no, airport, slot_time, weeks_csv,
                     fingerprint, decision, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """,
                po.requestId(), po.season(), po.airline(), po.flightNo(), po.airport(), po.slotTime(),
                po.weeksCsv(), po.fingerprint(), po.decision(), po.status(), po.createdAt());
    }

    @Override
    public void insertAllocation(SlotAllocationPo po) {
        jdbcTemplate.update(
                """
                INSERT INTO slot_allocation
                    (season, airport, slot_time, week, airline, flight_no, request_id, basis, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                """,
                po.season(), po.airport(), po.slotTime(), po.week(), po.airline(), po.flightNo(),
                po.requestId(), po.basis());
    }

    @Override
    public List<SlotAllocationPo> findActiveAllocations(String season, String airport, String slotTime,
                                                        List<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(weeks.size(), "?"));
        String sql = """
                SELECT id, season, airport, slot_time, week, airline, flight_no, request_id, basis, active
                FROM slot_allocation
                WHERE season = ? AND airport = ? AND slot_time = ? AND active = 1 AND week IN (%s)
                ORDER BY week
                """.formatted(placeholders);
        Object[] args = new Object[3 + weeks.size()];
        args[0] = season;
        args[1] = airport;
        args[2] = slotTime;
        for (int i = 0; i < weeks.size(); i++) {
            args[3 + i] = weeks.get(i);
        }
        return jdbcTemplate.query(sql, ALLOCATION_MAPPER, args);
    }

    @Override
    public List<SlotAllocationPo> findActiveAllocationsByRequestId(String requestId) {
        return jdbcTemplate.query(
                """
                SELECT id, season, airport, slot_time, week, airline, flight_no, request_id, basis, active
                FROM slot_allocation
                WHERE request_id = ? AND active = 1
                ORDER BY week
                """,
                ALLOCATION_MAPPER, requestId);
    }

    @Override
    public int deactivateAllocations(String requestId) {
        return jdbcTemplate.update(
                "UPDATE slot_allocation SET active = 0 WHERE request_id = ? AND active = 1",
                requestId);
    }

    @Override
    public int markSeriesReleased(String requestId, long expectedVersion) {
        return jdbcTemplate.update(
                "UPDATE slot_series SET status = 'RELEASED', version = version + 1 WHERE request_id = ? AND status = 'HELD' AND version = ?",
                requestId, expectedVersion);
    }

    @Override
    public List<SlotAllocationPo> findActiveAllocationsBySeason(String season) {
        return jdbcTemplate.query(
                """
                SELECT id, season, airport, slot_time, week, airline, flight_no, request_id, basis, active
                FROM slot_allocation
                WHERE season = ? AND active = 1
                ORDER BY airport, slot_time, week
                """,
                ALLOCATION_MAPPER, season);
    }

    @Override
    public void insertSnapshot(String season, List<SlotAllocationPo> allocations) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO slot_snapshot (season, airport, slot_time, week, airline, flight_no, request_id, basis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                allocations,
                allocations.size(),
                (ps, po) -> {
                    ps.setString(1, season);
                    ps.setString(2, po.airport());
                    ps.setString(3, po.slotTime());
                    ps.setInt(4, po.week());
                    ps.setString(5, po.airline());
                    ps.setString(6, po.flightNo());
                    ps.setString(7, po.requestId());
                    ps.setString(8, po.basis());
                });
    }

    @Override
    public List<SnapshotAllocationPo> findSnapshot(String season) {
        return jdbcTemplate.query(
                """
                SELECT id, season, airport, slot_time, week, airline, flight_no, request_id, basis
                FROM slot_snapshot
                WHERE season = ?
                ORDER BY airport, slot_time, week
                """,
                SNAPSHOT_MAPPER, season);
    }
}
