package com.example.starter.ride;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.ride.dto.RideDtos.CheckItem;

/**
 * 日检清单、试运行记录、运行读数与启停链事件仓储，全部可复算。
 */
@Repository
public class RideRecordRepository {

    private final JdbcTemplate jdbc;

    public RideRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 记录一次日检及其清单明细，返回日检记录ID。 */
    public long insertDailyCheck(long rideId, String requestId, LocalDate checkDate,
            boolean allPassed, List<CheckItem> items) {
        jdbc.update("INSERT INTO daily_check (ride_id, request_id, check_date, all_passed)"
                + " VALUES (?, ?, ?, ?)", rideId, requestId, checkDate, allPassed);
        Long checkId = jdbc.queryForObject("SELECT MAX(id) FROM daily_check WHERE ride_id = ?",
                Long.class, rideId);
        for (CheckItem item : items) {
            jdbc.update("INSERT INTO daily_check_item (check_id, item_name, passed) VALUES (?, ?, ?)",
                    checkId, item.itemName(), item.passed());
        }
        return checkId;
    }

    /** 记录一次试运行，seq 为当日第几次。 */
    public void insertTrialRun(long rideId, String requestId, int seq, boolean normal) {
        jdbc.update("INSERT INTO trial_run (ride_id, request_id, seq, normal) VALUES (?, ?, ?, ?)",
                rideId, requestId, seq, normal);
    }

    /** 记录一条运行读数；late=true 表示归档后迟到数据，warning=true 表示证书失效仅记录告警。 */
    public void insertReading(long rideId, int loadValue, int speedValue,
            boolean exceeded, boolean late, boolean warning) {
        jdbc.update("INSERT INTO runtime_reading (ride_id, load_value, speed_value, exceeded,"
                + " late, warning) VALUES (?, ?, ?, ?, ?, ?)",
                rideId, loadValue, speedValue, exceeded, late, warning);
    }

    /** 追加启停链事件。 */
    public void insertEvent(long rideId, String eventType, String detail) {
        jdbc.update("INSERT INTO ride_event (ride_id, event_type, detail) VALUES (?, ?, ?)",
                rideId, eventType, detail);
    }

    public List<Map<String, Object>> listDailyChecks(long rideId) {
        return jdbc.queryForList(
                "SELECT id, request_id, check_date, all_passed, created_at"
                        + " FROM daily_check WHERE ride_id = ? ORDER BY id", rideId);
    }

    public List<Map<String, Object>> listDailyCheckItems(long checkId) {
        return jdbc.queryForList(
                "SELECT item_name, passed FROM daily_check_item WHERE check_id = ? ORDER BY id",
                checkId);
    }

    public List<Map<String, Object>> listTrialRuns(long rideId) {
        return jdbc.queryForList(
                "SELECT id, request_id, seq, normal, created_at"
                        + " FROM trial_run WHERE ride_id = ? ORDER BY id", rideId);
    }

    public List<Map<String, Object>> listEvents(long rideId) {
        return jdbc.queryForList(
                "SELECT id, event_type, detail, created_at"
                        + " FROM ride_event WHERE ride_id = ? ORDER BY id", rideId);
    }

    public List<Map<String, Object>> listReadings(long rideId) {
        return jdbc.queryForList(
                "SELECT id, load_value, speed_value, exceeded, warning, late, created_at"
                        + " FROM runtime_reading WHERE ride_id = ? ORDER BY id", rideId);
    }
}
