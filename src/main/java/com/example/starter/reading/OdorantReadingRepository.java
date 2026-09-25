package com.example.starter.reading;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 浓度检测读数仓储：封存后迟到数据不落本仓（仅写迟到事件），故日结统计无需额外过滤。
 */
@Repository
public class OdorantReadingRepository {

    private final JdbcTemplate jdbc;

    public OdorantReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long stationId, String requestId, java.time.LocalDateTime detectedAt,
                       BigDecimal gasFlowM3, BigDecimal concentrationMgM3, boolean qualified) {
        jdbc.update("""
                        INSERT INTO odorant_reading
                            (station_id, request_id, detected_at, gas_flow_m3, concentration_mg_m3, qualified)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                stationId, requestId, detectedAt, gasFlowM3, concentrationMgM3, qualified);
    }

    /**
     * 统计业务日期内读数的流量合计 m³。
     */
    public BigDecimal sumFlowByDate(Long stationId, LocalDate bizDate) {
        BigDecimal sum = jdbc.queryForObject("""
                        SELECT COALESCE(SUM(gas_flow_m3), 0)
                        FROM odorant_reading
                        WHERE station_id = ?
                          AND detected_at >= ? AND detected_at < ?
                        """,
                BigDecimal.class, stationId, bizDate.atStartOfDay(), bizDate.plusDays(1).atStartOfDay());
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
