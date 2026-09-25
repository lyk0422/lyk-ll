package com.example.starter.waterlogging.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.waterlogging.po.WarningLevel;

/**
 * 预警级别表数据访问。
 */
@Repository
public class WarningLevelRepository {

    public WarningLevelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final JdbcTemplate jdbcTemplate;

    /** 按级别编码查询合成预警级别。 */
    public Optional<WarningLevel> findByCode(String levelCode) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    SELECT id, level_code, level_name, sort_no, trigger_pre_drain
                    FROM warning_level WHERE level_code = ?
                    """, (rs, n) -> new WarningLevel(
                    rs.getLong("id"),
                    rs.getString("level_code"),
                    rs.getString("level_name"),
                    rs.getInt("sort_no"),
                    rs.getBoolean("trigger_pre_drain")), levelCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查询全部级别（按严重程度升序），供复算/测试校验。 */
    public List<WarningLevel> findAll() {
        return jdbcTemplate.query("""
                SELECT id, level_code, level_name, sort_no, trigger_pre_drain
                FROM warning_level ORDER BY sort_no
                """, (rs, n) -> new WarningLevel(
                rs.getLong("id"),
                rs.getString("level_code"),
                rs.getString("level_name"),
                rs.getInt("sort_no"),
                rs.getBoolean("trigger_pre_drain")));
    }
}
