package com.example.starter.tailings.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 泄流方案及处置链守卫（生效唯一、执行唯一）存取。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 泄流方案行。
     *
     * @param id             主键
     * @param requestId      幂等请求ID
     * @param calcId         关联调洪演算ID
     * @param pondId         尾矿库ID
     * @param orifices       孔口组合JSON
     * @param totalDischarge 总泄量(立方米/秒)
     * @param status         EFFECTIVE生效/COMPLETED已完结
     * @param createdAt      创建时间
     */
    public record PlanRow(long id, String requestId, long calcId, long pondId, String orifices,
                          BigDecimal totalDischarge, String status, LocalDateTime createdAt) {
    }

    public long insertPlan(String requestId, long calcId, long pondId, String orifices,
                           BigDecimal totalDischarge, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO discharge_plan (request_id, calc_id, pond_id, orifices, total_discharge, status)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setLong(2, calcId);
            ps.setLong(3, pondId);
            ps.setString(4, orifices);
            ps.setBigDecimal(5, totalDischarge);
            ps.setString(6, status);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<PlanRow> findPlan(long id) {
        return jdbc.query(
                        "SELECT id, request_id, calc_id, pond_id, orifices, total_discharge, status, created_at"
                                + " FROM discharge_plan WHERE id = ?",
                        (rs, rowNum) -> new PlanRow(
                                rs.getLong("id"),
                                rs.getString("request_id"),
                                rs.getLong("calc_id"),
                                rs.getLong("pond_id"),
                                rs.getString("orifices"),
                                rs.getBigDecimal("total_discharge"),
                                rs.getString("status"),
                                rs.getObject("created_at", LocalDateTime.class)),
                        id)
                .stream()
                .findFirst();
    }

    public void updatePlanStatus(long id, String status) {
        jdbc.update("UPDATE discharge_plan SET status = ? WHERE id = ?", status, id);
    }

    /**
     * 登记生效方案守卫；pond_id 主键冲突时抛出 DuplicateKeyException。
     */
    public void insertActiveGuard(long pondId, long planId) {
        jdbc.update("INSERT INTO pond_active_plan (pond_id, plan_id) VALUES (?, ?)", pondId, planId);
    }

    public Optional<Long> findActivePlanId(long pondId) {
        return jdbc.query("SELECT plan_id FROM pond_active_plan WHERE pond_id = ?",
                        (rs, rowNum) -> rs.getLong("plan_id"), pondId)
                .stream()
                .findFirst();
    }

    public void deleteActiveGuard(long pondId) {
        jdbc.update("DELETE FROM pond_active_plan WHERE pond_id = ?", pondId);
    }

    /**
     * 登记执行中方案守卫；pond_id 主键冲突时抛出 DuplicateKeyException。
     */
    public void insertExecutingGuard(long pondId, long planId, long commandId) {
        jdbc.update("INSERT INTO pond_executing_plan (pond_id, plan_id, command_id) VALUES (?, ?, ?)",
                pondId, planId, commandId);
    }

    public Optional<Long> findExecutingPlanId(long pondId) {
        return jdbc.query("SELECT plan_id FROM pond_executing_plan WHERE pond_id = ?",
                        (rs, rowNum) -> rs.getLong("plan_id"), pondId)
                .stream()
                .findFirst();
    }

    public void deleteExecutingGuard(long pondId) {
        jdbc.update("DELETE FROM pond_executing_plan WHERE pond_id = ?", pondId);
    }
}
