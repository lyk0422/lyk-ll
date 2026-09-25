package com.example.starter.pressure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 联动任务仓储：调压与加注调整在同一事务内完成时置 COMPLETED。
 */
@Repository
public class LinkageTaskRepository {

    private final JdbcTemplate jdbc;

    public LinkageTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insertAccepted(Long stationId, String requestId, LocalDateTime detectedAt,
                               BigDecimal pressureBeforeMpa,
                               BigDecimal injectionRateBeforeMgM3, BigDecimal gasFlowM3) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO linkage_task
                                (station_id, request_id, detected_at, status, pressure_before_mpa,
                                 injection_rate_before_mg_m3, gas_flow_m3, created_at)
                            VALUES (?, ?, ?, 'ACCEPTED', ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setString(2, requestId);
            ps.setTimestamp(3, Timestamp.valueOf(detectedAt));
            ps.setBigDecimal(4, pressureBeforeMpa);
            ps.setBigDecimal(5, injectionRateBeforeMgM3);
            ps.setBigDecimal(6, gasFlowM3);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public void complete(Long taskId, BigDecimal pressureAfterMpa,
                         BigDecimal injectionRateAfterMgM3, BigDecimal injectionDoseMg) {
        jdbc.update("""
                        UPDATE linkage_task
                        SET status = 'COMPLETED', pressure_after_mpa = ?,
                            injection_rate_after_mg_m3 = ?, injection_dose_mg = ?,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                pressureAfterMpa, injectionRateAfterMgM3, injectionDoseMg, taskId);
    }

    public long countByRequest(String requestId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM linkage_task WHERE request_id = ?", Long.class, requestId);
        return count == null ? 0 : count;
    }

    public long countCompleted(Long stationId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM linkage_task WHERE station_id = ? AND status = 'COMPLETED'",
                Long.class, stationId);
        return count == null ? 0 : count;
    }
}
