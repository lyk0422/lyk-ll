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
 * 配额核销单的数据访问。
 */
@Repository
public class SettlementDao {

    /** 核销单行。 */
    public record SettlementRow(long id, String requestId, long voyageId, Instant settledAt) {
    }

    /** 核销明细行。 */
    public record SettlementItemRow(long settlementId, String speciesCode, BigDecimal quantityKg) {
    }

    private final JdbcTemplate jdbc;

    public SettlementDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 记录核销单，返回核销单 ID。 */
    public long insertSettlement(String requestId, long voyageId, Instant settledAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO settlement (request_id, voyage_id, settled_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, voyageId);
            ps.setTimestamp(3, Timestamp.from(settledAt));
            return ps;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    /** 记录核销明细。 */
    public void insertItems(List<SettlementItemRow> items) {
        jdbc.batchUpdate("INSERT INTO settlement_item (settlement_id, species_code, quantity_kg) VALUES (?, ?, ?)",
                items, items.size(),
                (ps, item) -> {
                    ps.setLong(1, item.settlementId());
                    ps.setString(2, item.speciesCode());
                    ps.setBigDecimal(3, item.quantityKg());
                });
    }

    /** 按航次查询核销单。 */
    public Optional<SettlementRow> findByVoyage(long voyageId) {
        List<SettlementRow> rows = jdbc.query(
                "SELECT id, request_id, voyage_id, settled_at FROM settlement WHERE voyage_id = ?",
                (rs, n) -> new SettlementRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getTimestamp(4).toInstant()),
                voyageId);
        return rows.stream().findFirst();
    }

    /** 核销单明细。 */
    public List<SettlementItemRow> itemsOf(long settlementId) {
        return jdbc.query("SELECT settlement_id, species_code, quantity_kg FROM settlement_item"
                        + " WHERE settlement_id = ?",
                (rs, n) -> new SettlementItemRow(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)),
                settlementId);
    }
}
