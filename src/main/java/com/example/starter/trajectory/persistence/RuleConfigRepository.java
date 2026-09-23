package com.example.starter.trajectory.persistence;

import com.example.starter.trajectory.domain.RuleConfig;
import com.example.starter.trajectory.domain.RuleStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 版本化规则配置仓储，基于 H2（MODE=MySQL）。
 */
@Repository
public class RuleConfigRepository {

    private static final RowMapper<RuleConfig> ROW_MAPPER = (rs, rowNum) -> new RuleConfig(
            rs.getString("rule_version"),
            rs.getDouble("max_time_gap"),
            rs.getDouble("max_speed"),
            rs.getDouble("max_acceleration"),
            rs.getDouble("max_curvature"),
            rs.getDouble("road_tolerance"),
            rs.getDouble("collision_distance"),
            RuleStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;

    public RuleConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RuleConfig> findByVersion(String ruleVersion) {
        try {
            RuleConfig config = jdbcTemplate.queryForObject(
                    "SELECT rule_version, max_time_gap, max_speed, max_acceleration, "
                            + "max_curvature, road_tolerance, collision_distance, status "
                            + "FROM rule_config WHERE rule_version = ?",
                    ROW_MAPPER, ruleVersion);
            return Optional.ofNullable(config);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean existsByVersion(String ruleVersion) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rule_config WHERE rule_version = ?",
                Integer.class, ruleVersion);
        return count != null && count > 0;
    }

    public void insert(RuleConfig config) {
        jdbcTemplate.update(
                "INSERT INTO rule_config (rule_version, max_time_gap, max_speed, max_acceleration, "
                        + "max_curvature, road_tolerance, collision_distance, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                config.ruleVersion(),
                config.maxTimeGapSeconds(),
                config.maxSpeed(),
                config.maxAcceleration(),
                config.maxCurvature(),
                config.roadTolerance(),
                config.collisionDistance(),
                config.status().name());
    }

    /**
     * 仅允许更新草稿状态配置的内容；已发布版本不可修改。
     */
    public void updateDraft(RuleConfig config) {
        jdbcTemplate.update(
                "UPDATE rule_config SET max_time_gap = ?, max_speed = ?, max_acceleration = ?, "
                        + "max_curvature = ?, road_tolerance = ?, collision_distance = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE rule_version = ? AND status = 'DRAFT'",
                config.maxTimeGapSeconds(),
                config.maxSpeed(),
                config.maxAcceleration(),
                config.maxCurvature(),
                config.roadTolerance(),
                config.collisionDistance(),
                config.ruleVersion());
    }

    public int updateStatus(String ruleVersion, String status) {
        return jdbcTemplate.update(
                "UPDATE rule_config SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE rule_version = ?",
                status, ruleVersion);
    }

    public List<RuleConfig> findAll() {
        return jdbcTemplate.query(
                "SELECT rule_version, max_time_gap, max_speed, max_acceleration, "
                        + "max_curvature, road_tolerance, collision_distance, status "
                        + "FROM rule_config ORDER BY rule_version",
                ROW_MAPPER);
    }
}
