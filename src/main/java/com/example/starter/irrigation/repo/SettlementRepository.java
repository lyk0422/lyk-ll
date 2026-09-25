package com.example.starter.irrigation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 欠供结转结算封存数据访问。
 */
@Repository
public class SettlementRepository {

    /**
     * 结算行。
     */
    public record SettlementRow(long id, long groupId, int roundNo) {
    }

    /**
     * 结算明细行。
     */
    public record SettlementDetailRow(long outletId, long plannedM3, long actualM3,
                                      long deficitM3, long carryoverM3, long voidedM3) {
    }

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (rs, n) ->
            new SettlementRow(rs.getLong("id"), rs.getLong("group_id"), rs.getInt("round_no"));

    private static final RowMapper<SettlementDetailRow> DETAIL_MAPPER = (rs, n) ->
            new SettlementDetailRow(rs.getLong("outlet_id"), rs.getLong("planned_m3"),
                    rs.getLong("actual_m3"), rs.getLong("deficit_m3"),
                    rs.getLong("carryover_m3"), rs.getLong("voided_m3"));

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SettlementRow> findByGroupAndRound(long groupId, int roundNo) {
        List<SettlementRow> rows = jdbc.query("SELECT id, group_id, round_no FROM round_settlement"
                + " WHERE group_id = ? AND round_no = ?", SETTLEMENT_MAPPER, groupId, roundNo);
        return rows.stream().findFirst();
    }

    public long insert(long groupId, int roundNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO round_settlement (group_id, round_no) VALUES (?, ?)",
                    new String[]{"id"});
            ps.setLong(1, groupId);
            ps.setInt(2, roundNo);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void insertDetail(long settlementId, long outletId, long plannedM3, long actualM3,
                             long deficitM3, long carryoverM3, long voidedM3) {
        jdbc.update("INSERT INTO settlement_detail (settlement_id, outlet_id, planned_m3, actual_m3,"
                        + " deficit_m3, carryover_m3, voided_m3) VALUES (?, ?, ?, ?, ?, ?, ?)",
                settlementId, outletId, plannedM3, actualM3, deficitM3, carryoverM3, voidedM3);
    }

    public List<SettlementDetailRow> findDetails(long settlementId) {
        return jdbc.query("SELECT outlet_id, planned_m3, actual_m3, deficit_m3, carryover_m3, voided_m3"
                + " FROM settlement_detail WHERE settlement_id = ? ORDER BY outlet_id", DETAIL_MAPPER, settlementId);
    }

    /**
     * 返回指定轮次已封存的结转水量：斗口 id → 结转补供水量（立方米）。
     */
    public Map<Long, Long> findCarryoverByGroupAndRound(long groupId, int roundNo) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.query("SELECT d.outlet_id, d.carryover_m3 FROM settlement_detail d"
                        + " JOIN round_settlement s ON d.settlement_id = s.id"
                        + " WHERE s.group_id = ? AND s.round_no = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        result.put(rs.getLong("outlet_id"), rs.getLong("carryover_m3")),
                groupId, roundNo);
        return result;
    }
}
