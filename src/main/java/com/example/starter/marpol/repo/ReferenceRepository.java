package com.example.starter.marpol.repo;

import com.example.starter.marpol.domain.VoyageStatus;
import com.example.starter.marpol.model.LandMark;
import com.example.starter.marpol.model.Ship;
import com.example.starter.marpol.model.SpecialArea;
import com.example.starter.marpol.model.Voyage;
import com.example.starter.marpol.support.ServiceClock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 参考数据仓储：船舶、航次、合成特殊区域多边形与陆地参照点。
 */
@Repository
public class ReferenceRepository {

    private static final RowMapper<Ship> SHIP_MAPPER = (rs, n) -> new Ship(
            rs.getLong("id"),
            rs.getString("imo"),
            rs.getString("name"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<Voyage> VOYAGE_MAPPER = (rs, n) -> {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new Voyage(
                rs.getLong("id"),
                rs.getLong("ship_id"),
                rs.getString("voyage_no"),
                VoyageStatus.valueOf(rs.getString("status")),
                closed == null ? null : closed.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    };

    private final JdbcTemplate jdbc;
    private final ServiceClock clock;
    private final ObjectMapper objectMapper;

    public ReferenceRepository(JdbcTemplate jdbc, ServiceClock clock, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public Ship createShip(String imo, String name) {
        Instant now = clock.now();
        jdbc.update("INSERT INTO ship (imo, name, created_at) VALUES (?, ?, ?)",
                imo, name, Timestamp.from(now));
        return findShipByImo(imo);
    }

    public Ship findShipByImo(String imo) {
        List<Ship> ships = jdbc.query(
                "SELECT id, imo, name, created_at FROM ship WHERE imo = ?", SHIP_MAPPER, imo);
        return ships.isEmpty() ? null : ships.get(0);
    }

    public Ship findShipById(Long id) {
        List<Ship> ships = jdbc.query(
                "SELECT id, imo, name, created_at FROM ship WHERE id = ?", SHIP_MAPPER, id);
        return ships.isEmpty() ? null : ships.get(0);
    }

    public Voyage createVoyage(Ship ship, String voyageNo) {
        Instant now = clock.now();
        jdbc.update("INSERT INTO voyage (ship_id, voyage_no, status, closed_at, created_at) VALUES (?, ?, 'OPEN', NULL, ?)",
                ship.id(), voyageNo, Timestamp.from(now));
        return findVoyage(ship.id(), voyageNo);
    }

    public Voyage findVoyage(Long shipId, String voyageNo) {
        List<Voyage> voyages = jdbc.query(
                "SELECT id, ship_id, voyage_no, status, closed_at, created_at FROM voyage WHERE ship_id = ? AND voyage_no = ?",
                VOYAGE_MAPPER, shipId, voyageNo);
        return voyages.isEmpty() ? null : voyages.get(0);
    }

    public Voyage findVoyageById(Long voyageId) {
        List<Voyage> voyages = jdbc.query(
                "SELECT id, ship_id, voyage_no, status, closed_at, created_at FROM voyage WHERE id = ?",
                VOYAGE_MAPPER, voyageId);
        return voyages.isEmpty() ? null : voyages.get(0);
    }

    /**
     * 行级锁定航次后关闭，返回关闭后的航次；重复关闭保持幂等。
     */
    public Voyage closeVoyage(Long voyageId) {
        Instant now = clock.now();
        jdbc.update("UPDATE voyage SET status = 'CLOSED', closed_at = ? WHERE id = ? AND status = 'OPEN'",
                Timestamp.from(now), voyageId);
        return findVoyageById(voyageId);
    }

    public List<SpecialArea> findAllSpecialAreas() {
        return jdbc.query("SELECT id, name, polygon_json FROM special_area", (rs, n) -> {
            try {
                List<double[]> polygon = objectMapper.readValue(rs.getString("polygon_json"),
                        new TypeReference<List<double[]>>() {
                        });
                return new SpecialArea(rs.getLong("id"), rs.getString("name"), polygon);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("特殊区域多边形数据格式错误: " + rs.getString("name"), e);
            }
        });
    }

    public List<LandMark> findAllLandMarks() {
        return jdbc.query("SELECT id, name, longitude, latitude FROM land_mark",
                (rs, n) -> new LandMark(rs.getLong("id"), rs.getString("name"),
                        rs.getDouble("longitude"), rs.getDouble("latitude")));
    }
}
