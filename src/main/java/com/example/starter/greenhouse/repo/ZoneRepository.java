package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.po.Zone;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * 温室分区数据访问。
 */
@Repository
public class ZoneRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public ZoneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("zone").usingGeneratedKeyColumns("id");
    }

    public Zone insert(Zone zone) {
        var params = new HashMap<String, Object>();
        params.put("zone_code", zone.getZoneCode());
        params.put("name", zone.getName());
        params.put("created_at", java.sql.Timestamp.from(zone.getCreatedAt()));
        Number key = insert.executeAndReturnKey(params);
        zone.setId(key.longValue());
        return zone;
    }

    public Optional<Zone> findByCode(String zoneCode) {
        return jdbc.query("SELECT * FROM zone WHERE zone_code = ?", MAPPER, zoneCode)
                .stream().findFirst();
    }

    public Optional<Zone> findById(Long id) {
        return jdbc.query("SELECT * FROM zone WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    static final RowMapper<Zone> MAPPER = (ResultSet rs, int rowNum) -> {
        Zone z = new Zone();
        z.setId(rs.getLong("id"));
        z.setZoneCode(rs.getString("zone_code"));
        z.setName(rs.getString("name"));
        z.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return z;
    };
}
