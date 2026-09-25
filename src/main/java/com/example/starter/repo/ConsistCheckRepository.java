package com.example.starter.repo;

import com.example.starter.domain.ConsistCheckRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 编组核对记录数据访问。
 */
@Repository
public class ConsistCheckRepository {

    private static final RowMapper<ConsistCheckRow> ROW_MAPPER = (rs, i) -> new ConsistCheckRow(
            rs.getLong("id"),
            rs.getLong("operation_id"),
            rs.getInt("consist_version"),
            rs.getInt("expected_cars"),
            rs.getBigDecimal("expected_length"),
            rs.getBigDecimal("expected_weight"),
            rs.getInt("actual_cars"),
            rs.getBigDecimal("actual_length"),
            rs.getBigDecimal("actual_weight"),
            rs.getBoolean("matched"));

    private final JdbcTemplate jdbcTemplate;

    public ConsistCheckRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(long operationId, int consistVersion,
                       int expectedCars, BigDecimal expectedLength, BigDecimal expectedWeight,
                       int actualCars, BigDecimal actualLength, BigDecimal actualWeight,
                       boolean matched) {
        jdbcTemplate.update(
                "INSERT INTO consist_check (operation_id, consist_version, expected_cars,"
                        + " expected_length, expected_weight, actual_cars, actual_length,"
                        + " actual_weight, matched) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                operationId, consistVersion, expectedCars, expectedLength, expectedWeight,
                actualCars, actualLength, actualWeight, matched);
    }

    /**
     * 查询指定编组版本最近一次核对结果。
     */
    public Optional<ConsistCheckRow> findLatest(long operationId, int consistVersion) {
        return jdbcTemplate.query(
                        "SELECT id, operation_id, consist_version, expected_cars, expected_length,"
                                + " expected_weight, actual_cars, actual_length, actual_weight, matched"
                                + " FROM consist_check WHERE operation_id = ? AND consist_version = ?"
                                + " ORDER BY id DESC LIMIT 1",
                        ROW_MAPPER, operationId, consistVersion)
                .stream().findFirst();
    }
}
