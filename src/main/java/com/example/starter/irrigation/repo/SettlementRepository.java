package com.example.starter.irrigation.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 轮次封存与欠供结转明细仓储。封存记录唯一键为“轮次＋组”，
 * 一经写入不可更新，数据库层保证封存终态。
 */
@Repository
public class SettlementRepository {

    /** 封存主记录行。 */
    public record SettlementRow(long turnNo, String groupCode, String status, Instant sealedAt) {
    }

    /** 欠供结转明细行，单位均为立方米。 */
    public record CarryoverRow(String outletCode, long planCubicMeter, long deliveredCubicMeter,
                               long shortageCubicMeter, long carryoverCubicMeter,
                               long voidedCubicMeter, Instant sealedAt) {
    }

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查封存主记录；存在即表示该轮该组已封存终态。 */
    public Optional<SettlementRow> find(long turnNo, String groupCode) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT turn_no, group_code, status, sealed_at
                    FROM irr_settlement WHERE turn_no = ? AND group_code = ?
                    """, SETTLEMENT_MAPPER, turnNo, groupCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 查结转明细，按斗口编号升序。 */
    public List<CarryoverRow> findCarryovers(long turnNo, String groupCode) {
        return jdbc.query("""
                SELECT outlet_code, plan_cubic_meter, delivered_cubic_meter,
                       shortage_cubic_meter, carryover_cubic_meter, voided_cubic_meter, sealed_at
                FROM irr_carryover
                WHERE turn_no = ? AND group_code = ?
                ORDER BY outlet_code
                """, CARRYOVER_MAPPER, turnNo, groupCode);
    }

    /** 插入封存主记录（唯一索引保证并发封存仅一个成功，调用方须在事务内）。 */
    public void insertSettlement(long turnNo, String groupCode, Instant now) {
        jdbc.update("""
                INSERT INTO irr_settlement (turn_no, group_code, status, sealed_at)
                VALUES (?, ?, 'SEALED', ?)
                """, turnNo, groupCode, Timestamp.from(now));
    }

    /** 插入单条欠供结转明细（调用方须在事务内）。 */
    public void insertCarryover(long turnNo, String groupCode, CarryoverRow row) {
        jdbc.update("""
                INSERT INTO irr_carryover
                    (turn_no, group_code, outlet_code, plan_cubic_meter, delivered_cubic_meter,
                     shortage_cubic_meter, carryover_cubic_meter, voided_cubic_meter, sealed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turnNo, groupCode, row.outletCode(), row.planCubicMeter(),
                row.deliveredCubicMeter(), row.shortageCubicMeter(), row.carryoverCubicMeter(),
                row.voidedCubicMeter(), Timestamp.from(row.sealedAt()));
    }

    private static final RowMapper<SettlementRow> SETTLEMENT_MAPPER = (ResultSet rs, int rowNum) ->
            new SettlementRow(
                    rs.getLong("turn_no"),
                    rs.getString("group_code"),
                    rs.getString("status"),
                    rs.getTimestamp("sealed_at").toInstant());

    private static final RowMapper<CarryoverRow> CARRYOVER_MAPPER = (ResultSet rs, int rowNum) ->
            new CarryoverRow(
                    rs.getString("outlet_code"),
                    rs.getLong("plan_cubic_meter"),
                    rs.getLong("delivered_cubic_meter"),
                    rs.getLong("shortage_cubic_meter"),
                    rs.getLong("carryover_cubic_meter"),
                    rs.getLong("voided_cubic_meter"),
                    rs.getTimestamp("sealed_at").toInstant());
}
