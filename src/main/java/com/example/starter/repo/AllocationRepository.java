package com.example.starter.repo;

import java.util.List;

import com.example.starter.domain.AllocationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 分摊明细数据访问。全部轮次保留，支持复算。
 */
@Repository
public class AllocationRepository {

    private static final String COLUMNS =
            "id, command_id, round, turbine_id, available_kw, min_kw, headroom_kw, target_kw";

    private final JdbcTemplate jdbc;

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long commandId, int round, long turbineId, int availableKw, int minKw,
                       int headroomKw, int targetKw) {
        jdbc.update("INSERT INTO command_allocation (command_id, round, turbine_id, available_kw,"
                        + " min_kw, headroom_kw, target_kw) VALUES (?, ?, ?, ?, ?, ?, ?)",
                commandId, round, turbineId, availableKw, minKw, headroomKw, targetKw);
    }

    /**
     * 批量写入一轮分摊明细。
     */
    public void batchInsert(long commandId, int round,
                            List<com.example.starter.service.AllocationCalculator.Allocation> allocations) {
        for (com.example.starter.service.AllocationCalculator.Allocation a : allocations) {
            insert(commandId, round, a.turbineId(), a.availableKw(), a.minKw(), a.headroomKw(),
                    a.targetKw());
        }
    }

    public List<AllocationRow> findByCommandId(long commandId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM command_allocation WHERE command_id = ? ORDER BY round, turbine_id",
                this::map, commandId);
    }

    public List<AllocationRow> findByCommandIdAndRound(long commandId, int round) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM command_allocation WHERE command_id = ? AND round = ? ORDER BY turbine_id",
                this::map, commandId, round);
    }

    public int maxRound(long commandId) {
        Integer round = jdbc.queryForObject(
                "SELECT COALESCE(MAX(round), 0) FROM command_allocation WHERE command_id = ?",
                Integer.class, commandId);
        return round == null ? 0 : round;
    }

    /**
     * 查询某机组最新一轮的分摊目标，无分摊时返回 null。
     */
    public AllocationRow findLatestForTurbine(long commandId, long turbineId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM command_allocation WHERE command_id = ? AND turbine_id = ?"
                        + " ORDER BY round DESC LIMIT 1",
                this::map, commandId, turbineId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private AllocationRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AllocationRow(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8));
    }
}
