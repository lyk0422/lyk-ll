package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.model.Generator;
import com.example.starter.model.GeneratorStatus;

/**
 * 油机档案数据访问；分配通过条件更新保证并发下唯一成功。
 */
@Repository
public class GeneratorRepository {

    private final JdbcTemplate jdbcTemplate;

    public GeneratorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增油机，默认空闲，返回自增主键。
     */
    public long insert(String code, Instant now) {
        jdbcTemplate.update("INSERT INTO generator (code, status, created_at) VALUES (?,?,?)",
                code, GeneratorStatus.IDLE.name(), StationRepository.toOffsetDateTime(now));
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM generator WHERE code = ?", Long.class, code);
        return id == null ? -1L : id;
    }

    /**
     * 按编号查询油机。
     */
    public Optional<Generator> findByCode(String code) {
        return jdbcTemplate.query("SELECT * FROM generator WHERE code = ?", this::map, code)
                .stream().findFirst();
    }

    /**
     * 查询第一台空闲油机（按主键序）。
     */
    public Optional<Generator> findFirstIdle() {
        return jdbcTemplate.query(
                        "SELECT * FROM generator WHERE status = ? ORDER BY id LIMIT 1",
                        this::map, GeneratorStatus.IDLE.name())
                .stream().findFirst();
    }

    /**
     * 条件分配：仅当油机空闲时置为已分配，返回受影响行数（1 表示本事务抢到）。
     */
    public int tryAssign(String code) {
        return jdbcTemplate.update("UPDATE generator SET status = ? WHERE code = ? AND status = ?",
                GeneratorStatus.ASSIGNED.name(), code, GeneratorStatus.IDLE.name());
    }

    /**
     * 释放油机为空闲。
     */
    public void release(String code) {
        jdbcTemplate.update("UPDATE generator SET status = ? WHERE code = ?",
                GeneratorStatus.IDLE.name(), code);
    }

    private Generator map(ResultSet rs, int rowNum) throws SQLException {
        return new Generator(rs.getLong("id"), rs.getString("code"),
                GeneratorStatus.valueOf(rs.getString("status")),
                StationRepository.toInstant(rs, "created_at"));
    }
}
