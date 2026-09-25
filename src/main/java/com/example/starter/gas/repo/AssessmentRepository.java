package com.example.starter.gas.repo;

import com.example.starter.gas.domain.AssessmentRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 偏差考核明细数据访问：按站＋气量日唯一，支持逐日复算。
 */
@Repository
public class AssessmentRepository {

    private static final RowMapper<AssessmentRow> ROW_MAPPER = (rs, rowNum) -> new AssessmentRow(
            rs.getString("station_code"),
            rs.getDate("gas_date").toLocalDate(),
            rs.getString("voucher_no"),
            rs.getBigDecimal("nominated_energy"),
            rs.getBigDecimal("actual_energy"),
            rs.getBigDecimal("deviation"),
            rs.getBigDecimal("deviation_abs"),
            rs.getBigDecimal("tolerance_energy"),
            rs.getInt("exempt") == 1,
            rs.getBigDecimal("penalty"));

    private final JdbcTemplate jdbc;

    public AssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入或复算覆盖某日考核明细（MySQL/H2 兼容的 MERGE 语法）。 */
    public void upsert(AssessmentRow row) {
        jdbc.update(
                "MERGE INTO daily_assessment (station_code, gas_date, voucher_no, nominated_energy, "
                        + "actual_energy, deviation, deviation_abs, tolerance_energy, exempt, penalty, updated_at) "
                        + "KEY (station_code, gas_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                row.stationCode(), java.sql.Date.valueOf(row.gasDate()), row.voucherNo(),
                row.nominatedEnergy(), row.actualEnergy(), row.deviation(), row.deviationAbs(),
                row.toleranceEnergy(), row.exempt() ? 1 : 0, row.penalty());
    }

    /** 查询单日复算结果。 */
    public AssessmentRow find(String stationCode, java.time.LocalDate gasDate) {
        List<AssessmentRow> rows = jdbc.query(
                "SELECT * FROM daily_assessment WHERE station_code = ? AND gas_date = ?",
                ROW_MAPPER, stationCode, java.sql.Date.valueOf(gasDate));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某站考核明细（按气量日升序，结果稳定）。 */
    public List<AssessmentRow> findByStation(String stationCode) {
        return jdbc.query(
                "SELECT * FROM daily_assessment WHERE station_code = ? ORDER BY gas_date ASC",
                ROW_MAPPER, stationCode);
    }
}
