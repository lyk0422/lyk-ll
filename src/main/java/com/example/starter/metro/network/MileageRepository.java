package com.example.starter.metro.network;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 里程表查询：最短路径里程、按线路承运里程、最远站里程与线网最大里程。
 */
@Repository
public class MileageRepository {

    /** 某 OD 上一条线路的承运里程。 */
    public record LineMileage(String lineCode, int distanceKm) {
    }

    private final JdbcTemplate jdbcTemplate;

    public MileageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean stationExists(String code) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station WHERE code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    /**
     * 查询两站间最短路径里程（公里）；站点不存在时返回 null。
     */
    public Integer distanceKm(String from, String to) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT distance_km FROM mileage WHERE from_station = ? AND to_station = ?",
                (rs, rowNum) -> rs.getInt(1), from, to);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查询两站间最短路径按线路拆分的承运里程，按里程降序、线路编码升序排列（确定顺序）。
     */
    public List<LineMileage> lineMileages(String from, String to) {
        return jdbcTemplate.query(
                "SELECT line_code, distance_km FROM route_line_mileage"
                        + " WHERE from_station = ? AND to_station = ?"
                        + " ORDER BY distance_km DESC, line_code ASC",
                (rs, rowNum) -> new LineMileage(rs.getString(1), rs.getInt(2)), from, to);
    }

    /**
     * 从指定站出发的最远里程（公里），用于出闸缺失时按最远站票价暂记。
     */
    public int maxDistanceKmFrom(String station) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT MAX(distance_km) FROM mileage WHERE from_station = ?", Integer.class, station);
        return value == null ? 0 : value;
    }

    /**
     * 线网最大 OD 里程（公里），用于超时乘车按线网最高票价补收。
     */
    public int maxNetworkDistanceKm() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT MAX(distance_km) FROM mileage", Integer.class);
        return value == null ? 0 : value;
    }
}
