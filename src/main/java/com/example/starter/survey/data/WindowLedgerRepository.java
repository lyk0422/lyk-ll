package com.example.starter.survey.data;

import com.example.starter.survey.domain.SurveyType;
import com.example.starter.survey.domain.WindowLedgerRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 窗口台账数据访问：台账可整体清空并按规则复算重建。 */
@Repository
public class WindowLedgerRepository {

    private final JdbcTemplate jdbc;

    public WindowLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<WindowLedgerRow> MAPPER = (rs, n) -> new WindowLedgerRow(
            rs.getLong("id"),
            rs.getLong("ship_id"),
            SurveyType.valueOf(rs.getString("type")),
            rs.getDate("cycle_anchor").toLocalDate(),
            rs.getDate("window_start").toLocalDate(),
            rs.getDate("window_end").toLocalDate(),
            rs.getObject("extension_until") == null ? null : rs.getDate("extension_until").toLocalDate(),
            rs.getObject("survey_id") == null ? null : rs.getLong("survey_id"),
            rs.getTimestamp("computed_at").toInstant()
    );

    /** 清空台账（复算重建前调用）。 */
    public void deleteAll() {
        jdbc.update("DELETE FROM window_ledger");
    }

    /** 写入一行复算结果。 */
    public void insert(long shipId, SurveyType type, LocalDate anchor,
                       LocalDate start, LocalDate end, LocalDate extensionUntil,
                       Long surveyId, Instant now) {
        jdbc.update("""
                        INSERT INTO window_ledger (ship_id, type, cycle_anchor, window_start, window_end,
                                                   extension_until, survey_id, computed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                shipId, type.name(),
                java.sql.Date.valueOf(anchor),
                java.sql.Date.valueOf(start),
                java.sql.Date.valueOf(end),
                extensionUntil == null ? null : java.sql.Date.valueOf(extensionUntil),
                surveyId, Timestamp.from(now));
    }

    /** 全量台账（校验复算用）。 */
    public List<WindowLedgerRow> findAll() {
        return jdbc.query("SELECT * FROM window_ledger ORDER BY ship_id, type, cycle_anchor", MAPPER);
    }

    /** 指定船舶的台账。 */
    public List<WindowLedgerRow> findByShip(long shipId) {
        return jdbc.query("SELECT * FROM window_ledger WHERE ship_id = ? ORDER BY type, cycle_anchor",
                MAPPER, shipId);
    }
}
