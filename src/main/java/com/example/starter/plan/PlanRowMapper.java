package com.example.starter.plan;

import com.example.starter.api.dto.PlanResponse;
import com.example.starter.domain.PlanStatus;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * co_firing_plan 行映射：统一空值时间与日期的转换。
 */
public class PlanRowMapper implements RowMapper<PlanResponse> {

    public static final PlanRowMapper INSTANCE = new PlanRowMapper();

    @Override
    public PlanResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PlanResponse(
                rs.getLong("id"),
                rs.getString("kiln_id"),
                rs.getString("license_no"),
                PlanStatus.valueOf(rs.getString("status")).name(),
                rs.getBigDecimal("declared_heat_ratio"),
                rs.getBigDecimal("current_heat_ratio"),
                rs.getBigDecimal("floor_heat_ratio"),
                rs.getBigDecimal("chlorine_kg_h"),
                rs.getBigDecimal("sulfur_kg_h"),
                rs.getString("reject_reason"),
                toDateTime(rs.getTimestamp("declared_at")),
                toDateTime(rs.getTimestamp("executed_at")),
                toDateTime(rs.getTimestamp("sealed_at")),
                toDate(rs.getDate("ban_date")));
    }

    private LocalDateTime toDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private LocalDate toDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
