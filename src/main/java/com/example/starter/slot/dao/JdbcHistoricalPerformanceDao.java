package com.example.starter.slot.dao;

import com.example.starter.slot.po.HistoricalPerformancePo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 上一航季历史执行率 JDBC 实现。
 */
@Repository
public class JdbcHistoricalPerformanceDao implements HistoricalPerformanceDao {

    private static final String SELECT_SQL = """
            SELECT id, prev_season, airline, flight_no, airport, slot_time, execution_rate
            FROM historical_performance
            WHERE prev_season = ? AND airline = ? AND flight_no = ? AND airport = ? AND slot_time = ?
            """;

    private static final RowMapper<HistoricalPerformancePo> MAPPER = (rs, rowNum) ->
            new HistoricalPerformancePo(
                    rs.getLong("id"),
                    rs.getString("prev_season"),
                    rs.getString("airline"),
                    rs.getString("flight_no"),
                    rs.getString("airport"),
                    rs.getString("slot_time"),
                    rs.getDouble("execution_rate"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcHistoricalPerformanceDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<HistoricalPerformancePo> find(String prevSeason, String airline, String flightNo,
                                                  String airport, String slotTime) {
        List<HistoricalPerformancePo> rows = jdbcTemplate.query(SELECT_SQL, MAPPER,
                prevSeason, airline, flightNo, airport, slotTime);
        return rows.stream().findFirst();
    }
}
