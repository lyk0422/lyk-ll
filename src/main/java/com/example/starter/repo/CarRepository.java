package com.example.starter.repo;

import com.example.starter.domain.CarRow;
import com.example.starter.dto.CarInput;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 编组车辆快照数据访问(按编组版本保存完整清单)。
 */
@Repository
public class CarRepository {

    private static final RowMapper<CarRow> ROW_MAPPER = (rs, i) -> new CarRow(
            rs.getLong("id"),
            rs.getLong("operation_id"),
            rs.getInt("consist_version"),
            rs.getString("car_no"),
            rs.getBigDecimal("length_coeff"),
            rs.getBigDecimal("weight_tons"),
            rs.getBoolean("brake_cutout"));

    private final JdbcTemplate jdbcTemplate;

    public CarRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(long operationId, int consistVersion, CarInput car) {
        jdbcTemplate.update(
                "INSERT INTO operation_car (operation_id, consist_version, car_no, length_coeff,"
                        + " weight_tons, brake_cutout) VALUES (?, ?, ?, ?, ?, ?)",
                operationId, consistVersion, car.carNo(), car.lengthCoeff(), car.weightTons(),
                car.brakeCutout());
    }

    public List<CarRow> findByOperationAndVersion(long operationId, int consistVersion) {
        return jdbcTemplate.query(
                "SELECT id, operation_id, consist_version, car_no, length_coeff, weight_tons,"
                        + " brake_cutout FROM operation_car"
                        + " WHERE operation_id = ? AND consist_version = ? ORDER BY id",
                ROW_MAPPER, operationId, consistVersion);
    }

    public List<CarRow> findByOperation(long operationId) {
        return jdbcTemplate.query(
                "SELECT id, operation_id, consist_version, car_no, length_coeff, weight_tons,"
                        + " brake_cutout FROM operation_car WHERE operation_id = ? ORDER BY consist_version, id",
                ROW_MAPPER, operationId);
    }
}
