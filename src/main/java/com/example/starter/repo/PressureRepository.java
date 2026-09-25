package com.example.starter.repo;

import com.example.starter.domain.PressureReadingRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 风压读数数据访问(试验曲线复算来源; 销记后迟到数据仅记迟到事件)。
 */
@Repository
public class PressureRepository {

    private static final RowMapper<PressureReadingRow> ROW_MAPPER = (rs, i) -> new PressureReadingRow(
            rs.getLong("id"),
            rs.getLong("operation_id"),
            rs.getInt("pressure_kpa"),
            rs.getObject("occurred_at", LocalDateTime.class),
            rs.getBoolean("late"),
            rs.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public PressureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(long operationId, int pressureKpa, LocalDateTime occurredAt, boolean late) {
        jdbcTemplate.update(
                "INSERT INTO pressure_reading (operation_id, pressure_kpa, occurred_at, late)"
                        + " VALUES (?, ?, ?, ?)",
                operationId, pressureKpa, occurredAt, late);
    }

    public List<PressureReadingRow> findByOperation(long operationId) {
        return jdbcTemplate.query(
                "SELECT id, operation_id, pressure_kpa, occurred_at, late, created_at"
                        + " FROM pressure_reading WHERE operation_id = ? ORDER BY id",
                ROW_MAPPER, operationId);
    }
}
