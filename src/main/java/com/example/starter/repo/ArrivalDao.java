package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 进港报告的数据访问。
 */
@Repository
public class ArrivalDao {

    /** 进港报告行。 */
    public record ArrivalRow(long id, String requestId, long voyageId, Instant arrivalTime,
                             boolean deviationFlagged, String differenceNote) {
    }

    /** 卸渔明细行。 */
    public record ArrivalItemRow(long arrivalId, String speciesCode, BigDecimal unloadedKg) {
    }

    private final JdbcTemplate jdbc;

    public ArrivalDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 记录进港报告，返回报告 ID。 */
    public long insertReport(String requestId, long voyageId, Instant arrivalTime,
                             boolean deviationFlagged, String differenceNote) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO arrival_report (request_id, voyage_id, arrival_time, deviation_flagged,"
                            + " difference_note, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, voyageId);
            ps.setTimestamp(3, Timestamp.from(arrivalTime));
            ps.setBoolean(4, deviationFlagged);
            ps.setString(5, differenceNote);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 记录卸渔明细。 */
    public void insertItems(List<ArrivalItemRow> items) {
        jdbc.batchUpdate("INSERT INTO arrival_item (arrival_id, species_code, unloaded_kg) VALUES (?, ?, ?)",
                items, items.size(),
                (ps, item) -> {
                    ps.setLong(1, item.arrivalId());
                    ps.setString(2, item.speciesCode());
                    ps.setBigDecimal(3, item.unloadedKg());
                });
    }

    /** 按航次查询进港报告。 */
    public Optional<ArrivalRow> findByVoyage(long voyageId) {
        List<ArrivalRow> rows = jdbc.query(
                "SELECT id, request_id, voyage_id, arrival_time, deviation_flagged, difference_note"
                        + " FROM arrival_report WHERE voyage_id = ?",
                (rs, n) -> new ArrivalRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getTimestamp(4).toInstant(), rs.getBoolean(5), rs.getString(6)),
                voyageId);
        return rows.stream().findFirst();
    }

    /** 报告的卸渔明细。 */
    public List<ArrivalItemRow> itemsOf(long arrivalId) {
        return jdbc.query("SELECT arrival_id, species_code, unloaded_kg FROM arrival_item WHERE arrival_id = ?",
                (rs, n) -> new ArrivalItemRow(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)),
                arrivalId);
    }
}
