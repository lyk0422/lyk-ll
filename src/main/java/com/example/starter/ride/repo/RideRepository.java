package com.example.starter.ride.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.ride.domain.Ride;

/**
 * 设备状态表仓储：行级锁保证同一设备状态链串行更新。
 */
@Repository
public class RideRepository {

    private static final RideRowMapper ROW_MAPPER = new RideRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public RideRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * SELECT ... FOR UPDATE 锁定并读取设备行。
     */
    public Ride findByIdForUpdate(String rideId) {
        List<Ride> rows = jdbcTemplate.query(
                "SELECT id, name, load_threshold, speed_threshold, status, biz_date, "
                        + "daily_check_passed, trial_passed_count, reopen_count, open_seq, "
                        + "archived_at, updated_at FROM ride WHERE id = ? FOR UPDATE",
                ROW_MAPPER, rideId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Ride findById(String rideId) {
        List<Ride> rows = jdbcTemplate.query(
                "SELECT id, name, load_threshold, speed_threshold, status, biz_date, "
                        + "daily_check_passed, trial_passed_count, reopen_count, open_seq, "
                        + "archived_at, updated_at FROM ride WHERE id = ?",
                ROW_MAPPER, rideId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(Ride ride) {
        jdbcTemplate.update(
                "INSERT INTO ride (id, name, load_threshold, speed_threshold, status, biz_date, "
                        + "daily_check_passed, trial_passed_count, reopen_count, open_seq, "
                        + "archived_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ride.getId(), ride.getName(), ride.getLoadThreshold(), ride.getSpeedThreshold(),
                ride.getStatus(), ride.getBizDate(), ride.isDailyCheckPassed(),
                ride.getTrialPassedCount(), ride.getReopenCount(), ride.getOpenSeq(),
                ride.getArchivedAt() == null ? null : Timestamp.from(ride.getArchivedAt()),
                Timestamp.from(ride.getUpdatedAt()));
    }

    public void updateState(Ride ride) {
        jdbcTemplate.update(
                "UPDATE ride SET status = ?, biz_date = ?, daily_check_passed = ?, "
                        + "trial_passed_count = ?, reopen_count = ?, open_seq = ?, "
                        + "archived_at = ?, updated_at = ? WHERE id = ?",
                ride.getStatus(), ride.getBizDate(), ride.isDailyCheckPassed(),
                ride.getTrialPassedCount(), ride.getReopenCount(), ride.getOpenSeq(),
                ride.getArchivedAt() == null ? null : Timestamp.from(ride.getArchivedAt()),
                Timestamp.from(ride.getUpdatedAt()), ride.getId());
    }

    public long countOpenEvents(String rideId, LocalDate bizDate) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM open_event WHERE ride_id = ? AND biz_date = ?",
                Long.class, rideId, bizDate);
        return count == null ? 0L : count;
    }

    private static final class RideRowMapper implements RowMapper<Ride> {

        @Override
        public Ride mapRow(ResultSet rs, int rowNum) throws SQLException {
            Ride ride = new Ride();
            ride.setId(rs.getString("id"));
            ride.setName(rs.getString("name"));
            ride.setLoadThreshold(rs.getDouble("load_threshold"));
            ride.setSpeedThreshold(rs.getDouble("speed_threshold"));
            ride.setStatus(rs.getString("status"));
            ride.setBizDate(rs.getObject("biz_date", LocalDate.class));
            ride.setDailyCheckPassed(rs.getBoolean("daily_check_passed"));
            ride.setTrialPassedCount(rs.getInt("trial_passed_count"));
            ride.setReopenCount(rs.getInt("reopen_count"));
            ride.setOpenSeq(rs.getInt("open_seq"));
            Timestamp archivedAt = rs.getTimestamp("archived_at");
            ride.setArchivedAt(archivedAt == null ? null : archivedAt.toInstant());
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            ride.setUpdatedAt(updatedAt == null ? null : updatedAt.toInstant());
            return ride;
        }
    }
}
