package com.example.starter.repo;

import com.example.starter.domain.BrakeTestRow;
import com.example.starter.domain.TestPhase;
import com.example.starter.domain.TestResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

/**
 * 制动试验记录数据访问。
 */
@Repository
public class BrakeTestRepository {

    private static final RowMapper<BrakeTestRow> ROW_MAPPER = (rs, i) -> new BrakeTestRow(
            rs.getLong("id"),
            rs.getLong("operation_id"),
            rs.getInt("consist_version"),
            TestPhase.valueOf(rs.getString("phase")),
            rs.getInt("reduction_kpa"),
            (Boolean) rs.getObject("all_cars_braked"),
            (Boolean) rs.getObject("emergency_occurred"),
            TestResult.valueOf(rs.getString("result")),
            rs.getBoolean("valid"),
            rs.getObject("created_at", java.time.LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public BrakeTestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(long operationId, int consistVersion, TestPhase phase, int reductionKpa,
                       Boolean allCarsBraked, Boolean emergencyOccurred, TestResult result) {
        jdbcTemplate.update(
                "INSERT INTO brake_test (operation_id, consist_version, phase, reduction_kpa,"
                        + " all_cars_braked, emergency_occurred, result, valid)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)",
                new Object[]{operationId, consistVersion, phase.name(), reductionKpa,
                        allCarsBraked, emergencyOccurred, result.name()},
                new int[]{Types.BIGINT, Types.INTEGER, Types.VARCHAR, Types.INTEGER,
                        Types.BOOLEAN, Types.BOOLEAN, Types.VARCHAR});
    }

    /**
     * 编组变更后作废全部有效结论(与摘挂在同一事务内执行)。
     *
     * @return 作废的记录数
     */
    public int invalidateByOperation(long operationId) {
        return jdbcTemplate.update(
                "UPDATE brake_test SET valid = FALSE WHERE operation_id = ? AND valid = TRUE",
                operationId);
    }

    /**
     * 是否存在指定编组版本上有效的合格结论。
     */
    public boolean existsValidPass(long operationId, int consistVersion, TestPhase phase) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM brake_test WHERE operation_id = ? AND consist_version = ?"
                        + " AND phase = ? AND result = 'PASS' AND valid = TRUE",
                Integer.class, operationId, consistVersion, phase.name());
        return count != null && count > 0;
    }

    public List<BrakeTestRow> findByOperation(long operationId) {
        return jdbcTemplate.query(
                "SELECT id, operation_id, consist_version, phase, reduction_kpa, all_cars_braked,"
                        + " emergency_occurred, result, valid, created_at FROM brake_test"
                        + " WHERE operation_id = ? ORDER BY id",
                ROW_MAPPER, operationId);
    }
}
