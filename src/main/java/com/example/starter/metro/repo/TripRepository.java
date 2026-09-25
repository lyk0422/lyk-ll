package com.example.starter.metro.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.metro.domain.Trip;

/**
 * 行程数据访问：OPEN/PROVISIONAL/SETTLED 状态流转均落库，可逐笔复算。
 */
@Repository
public class TripRepository {

    private static final RowMapper<Trip> MAPPER = (rs, rowNum) -> new Trip(
            rs.getLong("id"),
            rs.getString("ticket_id"),
            rs.getString("entry_tx_no"),
            rs.getString("entry_station"),
            rs.getTimestamp("entry_time").toLocalDateTime(),
            rs.getString("exit_tx_no"),
            rs.getString("exit_station"),
            rs.getTimestamp("exit_time") == null ? null : rs.getTimestamp("exit_time").toLocalDateTime(),
            rs.getObject("distance_km") == null ? null : rs.getInt("distance_km"),
            rs.getObject("chain_cum_km") == null ? null : rs.getInt("chain_cum_km"),
            rs.getObject("fare_fen") == null ? null : rs.getLong("fare_fen"),
            rs.getObject("provisional_fare_fen") == null ? null : rs.getLong("provisional_fare_fen"),
            rs.getString("status"),
            rs.getDate("batch_date") == null ? null : rs.getDate("batch_date").toLocalDate());

    private final JdbcTemplate jdbcTemplate;

    public TripRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新建 OPEN 行程，返回生成的主键。
     */
    public long insertOpen(String ticketId, String entryTxNo, String entryStation,
            LocalDateTime entryTime, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO trip(ticket_id, entry_tx_no, entry_station, entry_time, status,"
                            + " created_at, updated_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, ticketId);
            ps.setString(2, entryTxNo);
            ps.setString(3, entryStation);
            ps.setTimestamp(4, Timestamp.valueOf(entryTime));
            ps.setString(5, Trip.STATUS_OPEN);
            ps.setTimestamp(6, Timestamp.valueOf(now));
            ps.setTimestamp(7, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查找票卡进行中（OPEN 或 PROVISIONAL）的行程并加行锁，用于出闸结算串行化。
     */
    public Optional<Trip> findOpenOrProvisionalForUpdate(String ticketId) {
        List<Trip> rows = jdbcTemplate.query(
                "SELECT * FROM trip WHERE ticket_id = ? AND status IN ('OPEN','PROVISIONAL')"
                        + " ORDER BY entry_time DESC LIMIT 1 FOR UPDATE",
                MAPPER, ticketId);
        return rows.stream().findFirst();
    }

    /**
     * 查找票卡在指定时间之前最近一次已结算行程，用于换乘链判定。
     */
    public Optional<Trip> findLatestSettledBefore(String ticketId, LocalDateTime time) {
        List<Trip> rows = jdbcTemplate.query(
                "SELECT * FROM trip WHERE ticket_id = ? AND status = 'SETTLED' AND exit_time <= ?"
                        + " ORDER BY exit_time DESC LIMIT 1",
                MAPPER, ticketId, Timestamp.valueOf(time));
        return rows.stream().findFirst();
    }

    /**
     * 查找进闸日期不晚于指定日期的全部 OPEN 行程，用于日结暂记。
     */
    public List<Trip> findOpenTripsEnteredOnOrBefore(LocalDate date) {
        return jdbcTemplate.query(
                "SELECT * FROM trip WHERE status = 'OPEN' AND entry_time < ? ORDER BY id FOR UPDATE",
                MAPPER, Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

    /**
     * 日结暂记：OPEN -> PROVISIONAL，记录暂记金额与入账批次。
     */
    public void markProvisional(long id, long provisionalFareFen, LocalDate batchDate, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE trip SET status = 'PROVISIONAL', provisional_fare_fen = ?, batch_date = ?,"
                        + " updated_at = ? WHERE id = ?",
                provisionalFareFen, java.sql.Date.valueOf(batchDate), Timestamp.valueOf(now), id);
    }

    /**
     * 按实结算：OPEN/PROVISIONAL -> SETTLED，写入出闸信息、里程、链累计与实收金额。
     */
    public void markSettled(long id, String exitTxNo, String exitStation, LocalDateTime exitTime,
            int distanceKm, int chainCumKm, long fareFen, LocalDate batchDate, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE trip SET status = 'SETTLED', exit_tx_no = ?, exit_station = ?, exit_time = ?,"
                        + " distance_km = ?, chain_cum_km = ?, fare_fen = ?, batch_date = ?, updated_at = ?"
                        + " WHERE id = ?",
                exitTxNo, exitStation, Timestamp.valueOf(exitTime), distanceKm, chainCumKm, fareFen,
                java.sql.Date.valueOf(batchDate), Timestamp.valueOf(now), id);
    }

    public Optional<Trip> findById(long id) {
        List<Trip> rows = jdbcTemplate.query("SELECT * FROM trip WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }
}
