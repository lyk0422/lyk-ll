package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.model.Station;

/**
 * 基站档案数据访问。
 */
@Repository
public class StationRepository {

    private final JdbcTemplate jdbcTemplate;

    public StationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新增基站，返回自增主键。
     */
    public long insert(String code, java.math.BigDecimal loadKw, java.math.BigDecimal capacityKwh,
                       Instant now) {
        jdbcTemplate.update(
                "INSERT INTO station (code, load_kw, battery_capacity_kwh, created_at) VALUES (?,?,?,?)",
                code, loadKw, capacityKwh, toOffsetDateTime(now));
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM station WHERE code = ?", Long.class, code);
        return id == null ? -1L : id;
    }

    /**
     * 按编码查询基站。
     */
    public Optional<Station> findByCode(String code) {
        return jdbcTemplate.query("SELECT * FROM station WHERE code = ?", this::map, code)
                .stream().findFirst();
    }

    /**
     * 按编码查询并加行锁（SELECT ... FOR UPDATE），用作基站级串行化互斥。
     */
    public Optional<Station> findByCodeForUpdate(String code) {
        return jdbcTemplate.query("SELECT * FROM station WHERE code = ? FOR UPDATE", this::map, code)
                .stream().findFirst();
    }

    private Station map(ResultSet rs, int rowNum) throws SQLException {
        return new Station(rs.getLong("id"), rs.getString("code"), rs.getBigDecimal("load_kw"),
                rs.getBigDecimal("battery_capacity_kwh"), toInstant(rs, "created_at"));
    }

    static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
