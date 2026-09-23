package com.example.starter.trajectory.store;

import com.example.starter.trajectory.dto.RuleConfig;
import com.example.starter.trajectory.exception.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 基于 H2（MODE=MySQL）的规则版本仓库；版本一经写入不提供更新入口，保证不可变。
 */
@Repository
public class JdbcRuleVersionRepository implements RuleVersionRepository {

    private static final RowMapper<RuleConfig> CONFIG_MAPPER = (rs, rowNum) -> new RuleConfig(
            rs.getBigDecimal("max_time_interval"),
            rs.getBigDecimal("max_speed"),
            rs.getBigDecimal("max_acceleration"),
            rs.getBigDecimal("max_curvature"),
            rs.getBigDecimal("road_tolerance"),
            rs.getBigDecimal("safety_distance"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcRuleVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(String ruleVersion, RuleConfig config) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO rule_version
                        (rule_version, max_time_interval, max_speed, max_acceleration,
                         max_curvature, road_tolerance, safety_distance)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    ruleVersion,
                    config.maxTimeInterval(),
                    config.maxSpeed(),
                    config.maxAcceleration(),
                    config.maxCurvature(),
                    config.roadTolerance(),
                    config.safetyDistance());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("规则版本已存在且不可修改: " + ruleVersion);
        }
    }

    @Override
    public RuleConfig findByVersion(String ruleVersion) {
        return jdbcTemplate.query("""
                        SELECT max_time_interval, max_speed, max_acceleration, max_curvature,
                               road_tolerance, safety_distance
                        FROM rule_version WHERE rule_version = ?
                        """,
                        CONFIG_MAPPER, ruleVersion)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
