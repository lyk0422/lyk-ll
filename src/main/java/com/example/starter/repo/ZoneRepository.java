package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 分区数据访问。
 */
@Repository
public class ZoneRepository {

    private final JdbcTemplate jdbcTemplate;

    public ZoneRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Zone(String zoneCode, String zoneName) {
    }

    private static final RowMapper<Zone> ZONE_MAPPER = (rs, n) ->
            new Zone(rs.getString("zone_code"), rs.getString("zone_name"));

    public void insert(String zoneCode, String zoneName, Instant now) {
        jdbcTemplate.update("INSERT INTO zone_info (zone_code, zone_name, created_at) VALUES (?, ?, ?)",
                zoneCode, zoneName, Timestamp.from(now));
    }

    public boolean exists(String zoneCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM zone_info WHERE zone_code = ?", Integer.class, zoneCode);
        return count != null && count > 0;
    }

    public List<Zone> findAll() {
        return jdbcTemplate.query(
                "SELECT zone_code, zone_name FROM zone_info ORDER BY zone_code", ZONE_MAPPER);
    }
}
