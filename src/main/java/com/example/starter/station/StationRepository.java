package com.example.starter.station;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 门站配置只读仓储（合成数据）。
 */
@Repository
public class StationRepository {

    private final JdbcTemplate jdbc;

    public StationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Station> findByCode(String stationCode) {
        return jdbc.query("""
                        SELECT id, station_code, name, pressure_low_limit_mpa, pressure_high_limit_mpa,
                               pressure_target_mpa, target_concentration_mg_m3, concentration_tolerance_pct
                        FROM station WHERE station_code = ?
                        """,
                        rs -> rs.next() ? Optional.of(new Station(
                                rs.getLong("id"),
                                rs.getString("station_code"),
                                rs.getString("name"),
                                rs.getBigDecimal("pressure_low_limit_mpa"),
                                rs.getBigDecimal("pressure_high_limit_mpa"),
                                rs.getBigDecimal("pressure_target_mpa"),
                                rs.getBigDecimal("target_concentration_mg_m3"),
                                rs.getBigDecimal("concentration_tolerance_pct"))) : Optional.empty(),
                        stationCode);
    }
}
