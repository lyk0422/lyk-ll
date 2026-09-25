package com.example.starter.persistence;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 力矩工具台账 JDBC 访问：日校验，calibration_date 等于使用当日（UTC）才有效。
 */
@Repository
public class ToolRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 工具行。 */
    public record ToolRow(long id, String toolCode, LocalDate calibrationDate) {
    }

    /** 登记或更新工具日校验日期（合成数据管理用，不参与业务幂等键）。 */
    public void upsert(String toolCode, LocalDate calibrationDate) {
        jdbcTemplate.update(
                "MERGE INTO torque_tool (tool_code, calibration_date) KEY (tool_code) VALUES (?, ?)",
                toolCode, Date.valueOf(calibrationDate));
    }

    public ToolRow findByCode(String toolCode) {
        List<ToolRow> rows = jdbcTemplate.query(
                "SELECT id, tool_code, calibration_date FROM torque_tool WHERE tool_code = ?",
                (rs, n) -> new ToolRow(
                        rs.getLong("id"), rs.getString("tool_code"),
                        rs.getDate("calibration_date").toLocalDate()),
                toolCode);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
