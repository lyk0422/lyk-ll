package com.example.starter.radio.repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Optional;

import com.example.starter.radio.model.DailyCheck;
import com.example.starter.radio.model.DoseCalibrator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 活度计及其日检记录数据访问。
 * 日检以 (calibrator_id, check_date) 唯一约束保证同一活度计同一日仅一个校准会话。
 */
@Repository
public class CalibratorRepository {

    private final JdbcTemplate jdbc;

    public CalibratorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertCalibrator(String code, String name, long createdAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dose_calibrator (code, name, created_at_epoch) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setLong(3, createdAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<DoseCalibrator> findCalibratorById(long id) {
        return jdbc.query("SELECT id, code, name FROM dose_calibrator WHERE id = ?",
                (rs, i) -> new DoseCalibrator(rs.getLong("id"), rs.getString("code"), rs.getString("name")),
                id).stream().findFirst();
    }

    public long insertDailyCheck(DailyCheck check, long createdAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO calibrator_daily_check (request_id, fingerprint, calibrator_id, check_date,"
                            + " reference_activity_bq, measured_activity_bq, deviation_ratio, passed, created_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, check.requestId());
            ps.setString(2, check.fingerprint());
            ps.setLong(3, check.calibratorId());
            ps.setDate(4, Date.valueOf(check.checkDate()));
            ps.setDouble(5, check.referenceActivityBq());
            ps.setDouble(6, check.measuredActivityBq());
            ps.setDouble(7, check.deviationRatio());
            ps.setBoolean(8, check.passed());
            ps.setLong(9, createdAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<DailyCheck> findDailyCheckByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM calibrator_daily_check WHERE request_id = ?",
                this::mapDailyCheck, requestId).stream().findFirst();
    }

    public Optional<DailyCheck> findDailyCheck(long calibratorId, LocalDate checkDate) {
        return jdbc.query("SELECT * FROM calibrator_daily_check WHERE calibrator_id = ? AND check_date = ?",
                this::mapDailyCheck, calibratorId, Date.valueOf(checkDate)).stream().findFirst();
    }

    private DailyCheck mapDailyCheck(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DailyCheck(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("calibrator_id"),
                rs.getDate("check_date").toLocalDate(),
                rs.getDouble("reference_activity_bq"),
                rs.getDouble("measured_activity_bq"),
                rs.getDouble("deviation_ratio"),
                rs.getBoolean("passed"));
    }
}
