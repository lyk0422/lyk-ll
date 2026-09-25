package com.example.starter.survey.repo;

import com.example.starter.survey.domain.LedgerRow;
import com.example.starter.survey.model.SurveyEnums.LedgerState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 年度检验窗口台账数据访问，支持按复算结果重建。
 */
@Repository
public class LedgerRepo {

    private final JdbcTemplate jdbc;

    public LedgerRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LedgerRow find(long shipId, int cycleYear) {
        List<LedgerRow> rows = jdbc.query(
                "SELECT * FROM annual_window_ledger WHERE ship_id = ? AND cycle_year = ?",
                this::map, shipId, cycleYear);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<LedgerRow> findByShip(long shipId) {
        return jdbc.query(
                "SELECT * FROM annual_window_ledger WHERE ship_id = ? ORDER BY cycle_year",
                this::map, shipId);
    }

    public void deleteByShip(long shipId) {
        jdbc.update("DELETE FROM annual_window_ledger WHERE ship_id = ?", shipId);
    }

    /** 存在则更新，不存在则插入（uk_ledger_cycle）。 */
    public void upsert(long shipId, int cycleYear, LocalDate anniversaryDate,
                       LocalDate windowStart, LocalDate windowEnd, LocalDate extendedEnd,
                       Long surveyId, LocalDate boardedAt, LedgerState state) {
        int updated = jdbc.update(
                "UPDATE annual_window_ledger SET anniversary_date = ?, window_start = ?, "
                        + "window_end = ?, extended_end = ?, survey_id = ?, boarded_at = ?, "
                        + "state = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE ship_id = ? AND cycle_year = ?",
                Date.valueOf(anniversaryDate), Date.valueOf(windowStart), Date.valueOf(windowEnd),
                extendedEnd == null ? null : Date.valueOf(extendedEnd),
                surveyId, boardedAt == null ? null : Date.valueOf(boardedAt),
                state.name(), shipId, cycleYear);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO annual_window_ledger (ship_id, cycle_year, anniversary_date, "
                            + "window_start, window_end, extended_end, survey_id, boarded_at, state) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    shipId, cycleYear, Date.valueOf(anniversaryDate),
                    Date.valueOf(windowStart), Date.valueOf(windowEnd),
                    extendedEnd == null ? null : Date.valueOf(extendedEnd),
                    surveyId, boardedAt == null ? null : Date.valueOf(boardedAt),
                    state.name());
        }
    }

    private LedgerRow map(ResultSet rs, int n) throws SQLException {
        return new LedgerRow(
                rs.getLong("id"),
                rs.getLong("ship_id"),
                rs.getInt("cycle_year"),
                rs.getObject("anniversary_date", LocalDate.class),
                rs.getObject("window_start", LocalDate.class),
                rs.getObject("window_end", LocalDate.class),
                rs.getObject("extended_end", LocalDate.class),
                (Long) rs.getObject("survey_id"),
                rs.getObject("boarded_at", LocalDate.class),
                LedgerState.valueOf(rs.getString("state")),
                rs.getObject("updated_at", java.time.LocalDateTime.class));
    }
}
