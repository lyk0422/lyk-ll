package com.example.starter.marpol.repo;

import com.example.starter.marpol.domain.Ship;
import com.example.starter.marpol.domain.Voyage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 船舶与航次数据访问。
 */
@Repository
public class ShipRepository {

    private final JdbcTemplate jdbc;

    public ShipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertShip(Ship ship) {
        jdbc.update("INSERT INTO ship (ship_id, ship_name, created_at) VALUES (?, ?, ?)",
                ship.getShipId(), ship.getShipName(), Timestamp.from(ship.getCreatedAt()));
    }

    public Optional<Ship> findShip(String shipId) {
        List<Ship> list = jdbc.query(
                "SELECT ship_id, ship_name, created_at FROM ship WHERE ship_id = ?",
                shipRowMapper(), shipId);
        return list.stream().findFirst();
    }

    public void insertVoyage(Voyage voyage) {
        jdbc.update(
                "INSERT INTO voyage (ship_id, voyage_no, status, closed_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                voyage.getShipId(), voyage.getVoyageNo(), voyage.getStatus(),
                voyage.getClosedAt() == null ? null : Timestamp.from(voyage.getClosedAt()),
                Timestamp.from(voyage.getCreatedAt()));
    }

    public Optional<Voyage> findVoyage(String shipId, String voyageNo) {
        List<Voyage> list = jdbc.query(
                "SELECT ship_id, voyage_no, status, closed_at, created_at "
                        + "FROM voyage WHERE ship_id = ? AND voyage_no = ?",
                voyageRowMapper(), shipId, voyageNo);
        return list.stream().findFirst();
    }

    /**
     * 关闭航次（CAS：仅 OPEN 可关闭），返回受影响行数。
     */
    public int closeVoyage(String shipId, String voyageNo, Instant closedAt) {
        return jdbc.update(
                "UPDATE voyage SET status = 'CLOSED', closed_at = ? "
                        + "WHERE ship_id = ? AND voyage_no = ? AND status = 'OPEN'",
                Timestamp.from(closedAt), shipId, voyageNo);
    }

    private RowMapper<Ship> shipRowMapper() {
        return (rs, n) -> {
            Ship ship = new Ship();
            ship.setShipId(rs.getString("ship_id"));
            ship.setShipName(rs.getString("ship_name"));
            Timestamp created = rs.getTimestamp("created_at");
            ship.setCreatedAt(created == null ? null : created.toInstant());
            return ship;
        };
    }

    private RowMapper<Voyage> voyageRowMapper() {
        return (rs, n) -> {
            Voyage voyage = new Voyage();
            voyage.setShipId(rs.getString("ship_id"));
            voyage.setVoyageNo(rs.getString("voyage_no"));
            voyage.setStatus(rs.getString("status"));
            Timestamp closed = rs.getTimestamp("closed_at");
            voyage.setClosedAt(closed == null ? null : closed.toInstant());
            Timestamp created = rs.getTimestamp("created_at");
            voyage.setCreatedAt(created == null ? null : created.toInstant());
            return voyage;
        };
    }
}
