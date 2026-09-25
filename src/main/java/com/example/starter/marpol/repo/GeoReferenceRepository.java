package com.example.starter.marpol.repo;

import com.example.starter.marpol.domain.Port;
import com.example.starter.marpol.domain.SpecialArea;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 港口（岸线参考点）与特殊区域多边形数据访问。
 */
@Repository
public class GeoReferenceRepository {

    private final JdbcTemplate jdbc;

    public GeoReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Port> findAllPorts() {
        return jdbc.query(
                "SELECT port_code, port_name, latitude, longitude FROM port",
                portRowMapper());
    }

    public boolean portExists(String portCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM port WHERE port_code = ?", Integer.class, portCode);
        return count != null && count > 0;
    }

    public List<SpecialArea> findAllAreas() {
        List<AreaRow> areaRows = jdbc.query(
                "SELECT a.area_id, a.area_code, a.area_name, v.seq, v.latitude, v.longitude "
                        + "FROM special_area a "
                        + "JOIN special_area_vertex v ON v.area_id = a.area_id "
                        + "ORDER BY a.area_id, v.seq",
                (rs, n) -> new AreaRow(
                        rs.getLong("area_id"),
                        rs.getString("area_code"),
                        rs.getString("area_name"),
                        rs.getInt("seq"),
                        rs.getBigDecimal("latitude"),
                        rs.getBigDecimal("longitude")));
        return areaRows.stream().collect(java.util.stream.Collectors.groupingBy(AreaRow::areaId))
                .values().stream()
                .map(rows -> {
                    SpecialArea area = new SpecialArea();
                    AreaRow first = rows.get(0);
                    area.setAreaId(first.areaId());
                    area.setAreaCode(first.areaCode());
                    area.setAreaName(first.areaName());
                    area.setVertices(rows.stream()
                            .map(r -> new double[] {r.latitude().doubleValue(),
                                    r.longitude().doubleValue()})
                            .toList());
                    return area;
                })
                .toList();
    }

    private RowMapper<Port> portRowMapper() {
        return (rs, n) -> {
            Port port = new Port();
            port.setPortCode(rs.getString("port_code"));
            port.setPortName(rs.getString("port_name"));
            port.setLatitude(rs.getBigDecimal("latitude"));
            port.setLongitude(rs.getBigDecimal("longitude"));
            return port;
        };
    }

    private record AreaRow(long areaId, String areaCode, String areaName, int seq,
                           BigDecimal latitude, BigDecimal longitude) {
    }
}
