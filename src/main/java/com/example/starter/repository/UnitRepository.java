package com.example.starter.repository;

import com.example.starter.domain.HeatingUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 供热单元数据访问。
 */
@Repository
public class UnitRepository {

    private static final RowMapper<HeatingUnit> MAPPER = (rs, n) ->
            new HeatingUnit(rs.getLong("id"), rs.getLong("station_id"),
                    rs.getString("code"), rs.getString("name"));

    private final JdbcTemplate jdbc;

    public UnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 ID 查询单元。
     */
    public Optional<HeatingUnit> findById(long id) {
        return jdbc.query("SELECT id, station_id, code, name FROM heating_unit WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询换热站全部单元，按 ID 升序。
     */
    public List<HeatingUnit> findByStationId(long stationId) {
        return jdbc.query("SELECT id, station_id, code, name FROM heating_unit WHERE station_id = ? ORDER BY id",
                MAPPER, stationId);
    }
}
