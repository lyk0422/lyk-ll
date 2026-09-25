package com.example.starter.close;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 日结记录仓储：负责封存终态查询与勾稽结果落库（门站+业务日期唯一）。
 */
@Repository
public class DailyCloseRepository {

    private static final RowMapper<DailyCloseRow> ROW_MAPPER = new DailyCloseRowMapper();

    private final JdbcTemplate jdbc;

    public DailyCloseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 指定业务日期是否已封存（封存为终态，迟到检测仅记事件）。
     */
    public boolean isSealed(Long stationId, LocalDate bizDate) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_close WHERE station_id = ? AND biz_date = ? AND status = 'SEALED'",
                Integer.class, stationId, Date.valueOf(bizDate));
        return count != null && count > 0;
    }

    public Optional<DailyCloseRow> find(Long stationId, LocalDate bizDate) {
        return jdbc.query(BASE_SQL + " WHERE station_id = ? AND biz_date = ?",
                ROW_MAPPER, stationId, Date.valueOf(bizDate)).stream().findFirst();
    }

    /**
     * 行级锁定读取，串行化同一门站同一日的并发日结（无行时以插入唯一约束兜底）。
     */
    public Optional<DailyCloseRow> findForUpdate(Long stationId, LocalDate bizDate) {
        return jdbc.query(BASE_SQL + " WHERE station_id = ? AND biz_date = ? FOR UPDATE",
                ROW_MAPPER, stationId, Date.valueOf(bizDate)).stream().findFirst();
    }

    public void insert(Long stationId, LocalDate bizDate, String status, BigDecimal totalFlowM3,
                       BigDecimal expectedOdorantMg, BigDecimal reportedOdorantMg,
                       BigDecimal deviationPct, String sealRequestId, boolean sealed) {
        jdbc.update("""
                        INSERT INTO daily_close
                            (station_id, biz_date, status, total_flow_m3, expected_odorant_mg,
                             reported_odorant_mg, deviation_pct, seal_request_id, sealed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                stationId, Date.valueOf(bizDate), status, totalFlowM3, expectedOdorantMg,
                reportedOdorantMg, deviationPct, sealRequestId,
                sealed ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
    }

    public void update(Long stationId, LocalDate bizDate, String status, BigDecimal reportedOdorantMg,
                       BigDecimal deviationPct, String sealRequestId, boolean sealed) {
        jdbc.update("""
                        UPDATE daily_close
                        SET status = ?, reported_odorant_mg = ?, deviation_pct = ?,
                            seal_request_id = ?, sealed_at = ?
                        WHERE station_id = ? AND biz_date = ?
                        """,
                status, reportedOdorantMg, deviationPct, sealRequestId,
                sealed ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null,
                stationId, Date.valueOf(bizDate));
    }

    private static final String BASE_SQL = """
            SELECT station_id, biz_date, status, total_flow_m3, expected_odorant_mg,
                   reported_odorant_mg, deviation_pct, seal_request_id, sealed_at
            FROM daily_close
            """;

    private static class DailyCloseRowMapper implements RowMapper<DailyCloseRow> {
        @Override
        public DailyCloseRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DailyCloseRow(
                    rs.getLong("station_id"),
                    rs.getDate("biz_date").toLocalDate(),
                    rs.getString("status"),
                    rs.getBigDecimal("total_flow_m3"),
                    rs.getBigDecimal("expected_odorant_mg"),
                    rs.getBigDecimal("reported_odorant_mg"),
                    rs.getBigDecimal("deviation_pct"),
                    rs.getString("seal_request_id"),
                    rs.getTimestamp("sealed_at") == null
                            ? null : rs.getTimestamp("sealed_at").toLocalDateTime());
        }
    }
}
