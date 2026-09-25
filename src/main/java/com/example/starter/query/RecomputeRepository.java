package com.example.starter.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 复算只读仓储：直接读取原始检测读数、联动任务与指令，不使用任何派生缓存。
 */
@Repository
public class RecomputeRepository {

    private final JdbcTemplate jdbc;

    public RecomputeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReadingRow> findOdorantReadings(Long stationId, LocalDate bizDate) {
        return jdbc.query("""
                        SELECT request_id, detected_at, gas_flow_m3, concentration_mg_m3
                        FROM odorant_reading
                        WHERE station_id = ? AND detected_at >= ? AND detected_at < ?
                        ORDER BY detected_at, id
                        """,
                        (rs, n) -> new ReadingRow(
                                rs.getString("request_id"),
                                rs.getTimestamp("detected_at").toLocalDateTime(),
                                rs.getBigDecimal("gas_flow_m3"),
                                rs.getBigDecimal("concentration_mg_m3")),
                        stationId, bizDate.atStartOfDay(), bizDate.plusDays(1).atStartOfDay());
    }

    public List<PressureRow> findPressureReadings(Long stationId, LocalDate bizDate) {
        return jdbc.query("""
                        SELECT pr.request_id, pr.detected_at, pr.pressure_mpa, pr.grade,
                               pr.task_id, lt.status AS task_status, lt.pressure_after_mpa,
                               lt.injection_dose_mg,
                               (SELECT COUNT(*) FROM adjustment_issue ai
                                  WHERE ai.task_id = pr.task_id AND ai.issue_type = 'PRESSURE') AS p_cnt,
                               (SELECT COUNT(*) FROM adjustment_issue ai
                                  WHERE ai.task_id = pr.task_id AND ai.issue_type = 'INJECTION') AS i_cnt
                        FROM pressure_reading pr
                        LEFT JOIN linkage_task lt ON lt.id = pr.task_id
                        WHERE pr.station_id = ? AND pr.detected_at >= ? AND pr.detected_at < ?
                        ORDER BY pr.detected_at, pr.id
                        """,
                        (rs, n) -> new PressureRow(
                                rs.getString("request_id"),
                                rs.getTimestamp("detected_at").toLocalDateTime(),
                                rs.getBigDecimal("pressure_mpa"),
                                rs.getString("grade"),
                                (Long) rs.getObject("task_id"),
                                rs.getString("task_status"),
                                rs.getBigDecimal("pressure_after_mpa"),
                                rs.getBigDecimal("injection_dose_mg"),
                                rs.getInt("p_cnt") > 0,
                                rs.getInt("i_cnt") > 0),
                        stationId,
                        java.sql.Timestamp.valueOf(bizDate.atStartOfDay()),
                        java.sql.Timestamp.valueOf(bizDate.plusDays(1).atStartOfDay()));
    }

    public BigDecimal sumOdorantFlow(Long stationId, LocalDate bizDate) {
        BigDecimal sum = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(gas_flow_m3), 0) FROM odorant_reading
                        WHERE station_id = ? AND detected_at >= ? AND detected_at < ?
                        """,
                BigDecimal.class, stationId,
                java.sql.Timestamp.valueOf(bizDate.atStartOfDay()),
                java.sql.Timestamp.valueOf(bizDate.plusDays(1).atStartOfDay()));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    public int countOdorantReadings(Long stationId, LocalDate bizDate) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM odorant_reading
                        WHERE station_id = ? AND detected_at >= ? AND detected_at < ?
                        """,
                Integer.class, stationId,
                java.sql.Timestamp.valueOf(bizDate.atStartOfDay()),
                java.sql.Timestamp.valueOf(bizDate.plusDays(1).atStartOfDay()));
        return count == null ? 0 : count;
    }

    /**
     * 原始浓度读数行。
     */
    public record ReadingRow(String requestId, LocalDateTime detectedAt,
                             BigDecimal gasFlowM3, BigDecimal concentrationMgM3) {
    }

    /**
     * 原始压力读数及联动回查行。
     */
    public record PressureRow(String requestId, LocalDateTime detectedAt, BigDecimal pressureMpa,
                              String storedGrade, Long taskId, String taskStatus,
                              BigDecimal pressureAfterMpa, BigDecimal injectionDoseMg,
                              boolean pressureAdjusted, boolean injectionAdjusted) {
    }
}
