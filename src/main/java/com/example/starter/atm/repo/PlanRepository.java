package com.example.starter.atm.repo;

import com.example.starter.atm.domain.PlanStatus;
import com.example.starter.atm.domain.ReplenishPlan;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 加钞计划数据访问。
 * 唯一约束：plan_no、request_id、(atm_id, plan_utc_date)——并发编制同一 ATM 同日计划时数据库唯一受理。
 * 更新带乐观锁 version；状态推进必须基于读取时的版本提交。
 */
@Repository
public class PlanRepository {

    private static final RowMapper<ReplenishPlan> MAPPER = (rs, n) -> {
        ReplenishPlan p = new ReplenishPlan();
        p.setId(rs.getLong("id"));
        p.setPlanNo(rs.getString("plan_no"));
        p.setRequestId(rs.getString("request_id"));
        p.setAtmId(rs.getLong("atm_id"));
        p.setBoxId(rs.getLong("box_id"));
        p.setPlanUtcDate(rs.getString("plan_utc_date"));
        p.setPredictedAmount(rs.getLong("predicted_amount"));
        p.setPlannedAmount(rs.getLong("planned_amount"));
        p.setStatus(PlanStatus.valueOf(rs.getString("status")));
        p.setApprovedAt(getNullableLong(rs, "approved_at"));
        p.setEscortDeadline(getNullableLong(rs, "escort_deadline"));
        p.setCountedAmount(getNullableLong(rs, "counted_amount"));
        p.setCountedAt(getNullableLong(rs, "counted_at"));
        p.setCountGuard1Id(getNullableLong(rs, "count_guard1_id"));
        p.setCountGuard2Id(getNullableLong(rs, "count_guard2_id"));
        p.setDeviceAmount(getNullableLong(rs, "device_amount"));
        p.setDeviceConfirmedAt(getNullableLong(rs, "device_confirmed_at"));
        p.setReconciledAt(getNullableLong(rs, "reconciled_at"));
        p.setOverShortId(getNullableLong(rs, "over_short_id"));
        p.setSealedAt(getNullableLong(rs, "sealed_at"));
        int af = rs.getInt("active_flag");
        p.setActiveFlag(rs.wasNull() ? null : af == 1);
        p.setVersion(rs.getInt("version"));
        p.setCreatedAt(rs.getLong("created_at"));
        p.setUpdatedAt(rs.getLong("updated_at"));
        return p;
    };

    private static Long getNullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReplenishPlan findById(long id) {
        List<ReplenishPlan> list = jdbc.query("SELECT * FROM replenish_plan WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public ReplenishPlan findByPlanNo(String planNo) {
        List<ReplenishPlan> list = jdbc.query(
                "SELECT * FROM replenish_plan WHERE plan_no = ?", MAPPER, planNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public ReplenishPlan findByRequestId(String requestId) {
        List<ReplenishPlan> list = jdbc.query(
                "SELECT * FROM replenish_plan WHERE request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 插入新计划。request_id 重放或 (atm_id, plan_utc_date) 并发编制都会抛 {@link DuplicateKeyException}，
     * 由服务层区分幂等重放与唯一受理冲突。
     */
    public void insert(ReplenishPlan p) {
        jdbc.update(
                "INSERT INTO replenish_plan (plan_no, request_id, atm_id, box_id, plan_utc_date, "
                        + "predicted_amount, planned_amount, status, approved_at, escort_deadline, "
                        + "counted_amount, counted_at, count_guard1_id, count_guard2_id, "
                        + "device_amount, device_confirmed_at, reconciled_at, over_short_id, "
                        + "sealed_at, active_flag, version, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?)",
                p.getPlanNo(), p.getRequestId(), p.getAtmId(), p.getBoxId(), p.getPlanUtcDate(),
                p.getPredictedAmount(), p.getPlannedAmount(), p.getStatus().name(),
                p.getApprovedAt(), p.getEscortDeadline(),
                p.getCountedAmount(), p.getCountedAt(), p.getCountGuard1Id(), p.getCountGuard2Id(),
                p.getDeviceAmount(), p.getDeviceConfirmedAt(), p.getReconciledAt(),
                p.getOverShortId(), p.getSealedAt(), p.getCreatedAt(), p.getUpdatedAt());
    }

    /**
     * 乐观锁更新；影响行数为 0 表示期间有并发提交。
     * 终态（封存/押运异常）active_flag 置 NULL，退出同 ATM 同日唯一受理范围。
     */
    public int update(ReplenishPlan p) {
        boolean active = p.getStatus() != PlanStatus.SEALED
                && p.getStatus() != PlanStatus.ESCORT_ABNORMAL;
        return jdbc.update(
                "UPDATE replenish_plan SET status = ?, approved_at = ?, escort_deadline = ?, "
                        + "counted_amount = ?, counted_at = ?, count_guard1_id = ?, count_guard2_id = ?, "
                        + "device_amount = ?, device_confirmed_at = ?, reconciled_at = ?, "
                        + "over_short_id = ?, sealed_at = ?, active_flag = ?, "
                        + "version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND version = ?",
                p.getStatus().name(), p.getApprovedAt(), p.getEscortDeadline(),
                p.getCountedAmount(), p.getCountedAt(), p.getCountGuard1Id(), p.getCountGuard2Id(),
                p.getDeviceAmount(), p.getDeviceConfirmedAt(), p.getReconciledAt(),
                p.getOverShortId(), p.getSealedAt(), active ? 1 : null, p.getUpdatedAt(),
                p.getId(), p.getVersion());
    }

    /** 已审批且在押运时限前仍未双人打卡完成的计划（用于恰满 4 小时异常扫描）。 */
    public List<ReplenishPlan> findEscortTimedOut(long nowMillis) {
        return jdbc.query(
                "SELECT * FROM replenish_plan WHERE status IN ('APPROVED', 'IN_TRANSIT') "
                        + "AND escort_deadline IS NOT NULL AND escort_deadline <= ?",
                MAPPER, nowMillis);
    }

    /** 加钞台账：按创建顺序返回全部计划，供复算。 */
    public List<ReplenishPlan> findAll() {
        return jdbc.query("SELECT * FROM replenish_plan ORDER BY id", MAPPER);
    }
}
