package com.example.starter.command;

import com.example.starter.api.dto.CommandResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.RowMapper;

/**
 * reduction_command 行映射。
 */
public class CommandRowMapper implements RowMapper<CommandResponse> {

    public static final CommandRowMapper INSTANCE = new CommandRowMapper();

    @Override
    public CommandResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CommandResponse(
                rs.getLong("id"),
                rs.getLong("plan_id"),
                rs.getString("status"),
                toDateTime(rs.getTimestamp("hour_start")),
                rs.getBigDecimal("target_heat_ratio"),
                toDateTime(rs.getTimestamp("issued_at")),
                toDateTime(rs.getTimestamp("confirm_deadline")),
                toDateTime(rs.getTimestamp("confirmed_at")),
                toDateTime(rs.getTimestamp("escalated_at")));
    }

    private LocalDateTime toDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
