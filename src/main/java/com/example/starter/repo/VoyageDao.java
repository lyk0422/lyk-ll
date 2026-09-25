package com.example.starter.repo;

import com.example.starter.api.dto.ApiRequests.SpeciesQuantity;
import com.example.starter.domain.VoyageStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 航次与出港申报的数据访问。
 */
@Repository
public class VoyageDao {

    /** 航次行。 */
    public record VoyageRow(long id, String voyageNo, long vesselId, VoyageStatus status,
                            Instant departureTime, Instant arrivalTime, Instant closedAt) {
    }

    private final JdbcTemplate jdbc;

    public VoyageDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建航次（初始状态 DEPARTED），返回航次 ID。 */
    public long insertVoyage(long vesselId, String voyageNo, Instant departureTime) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO voyage (voyage_no, vessel_id, status, departure_time, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, voyageNo);
            ps.setLong(2, vesselId);
            ps.setString(3, VoyageStatus.DEPARTED.name());
            ps.setTimestamp(4, Timestamp.from(departureTime));
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 按 ID 查询航次。 */
    public Optional<VoyageRow> findVoyage(long voyageId) {
        List<VoyageRow> rows = jdbc.query(
                "SELECT id, voyage_no, vessel_id, status, departure_time, arrival_time, closed_at"
                        + " FROM voyage WHERE id = ?",
                (rs, n) -> new VoyageRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        VoyageStatus.valueOf(rs.getString(4)),
                        rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                voyageId);
        return rows.stream().findFirst();
    }

    /** 查询渔船进行中的航次（DEPARTED 在途；已进港待核销不视为进行中）。 */
    public Optional<VoyageRow> findInProgressVoyage(long vesselId) {
        List<VoyageRow> rows = jdbc.query(
                "SELECT id, voyage_no, vessel_id, status, departure_time, arrival_time, closed_at"
                        + " FROM voyage WHERE vessel_id = ? AND status = 'DEPARTED'",
                (rs, n) -> new VoyageRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        VoyageStatus.valueOf(rs.getString(4)),
                        rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                vesselId);
        return rows.stream().findFirst();
    }

    /** 航次状态流转；arrivalTime / closedAt 仅在对应流转时非空。 */
    public void updateStatus(long voyageId, VoyageStatus status, Instant arrivalTime, Instant closedAt) {
        jdbc.update("UPDATE voyage SET status = ?, arrival_time = COALESCE(?, arrival_time),"
                        + " closed_at = COALESCE(?, closed_at) WHERE id = ?",
                status.name(),
                arrivalTime == null ? null : Timestamp.from(arrivalTime),
                closedAt == null ? null : Timestamp.from(closedAt),
                voyageId);
    }

    /** 记录出港申报，返回申报 ID。 */
    public long insertDeclaration(String requestId, long voyageId, long vesselId, String fishingArea) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO departure_declaration (request_id, voyage_id, vessel_id, fishing_area, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, voyageId);
            ps.setLong(3, vesselId);
            ps.setString(4, fishingArea);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 记录申报明细。 */
    public void insertDeclarationItems(long declarationId, List<SpeciesQuantity> items) {
        jdbc.batchUpdate("INSERT INTO declaration_item (declaration_id, species_code, quantity_kg)"
                        + " VALUES (?, ?, ?)",
                items, items.size(),
                (ps, item) -> {
                    ps.setLong(1, declarationId);
                    ps.setString(2, item.speciesCode());
                    ps.setBigDecimal(3, item.quantityKg());
                });
    }
}
