package com.example.starter.irrigation.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 配水计划版本与生效指针数据访问。
 */
@Repository
public class PlanRepository {

    /**
     * 计划版本行。
     */
    public record PlanVersionRow(long id, long groupId, int roundNo, int versionNo,
                                 long totalWaterM3, String requestId, String fingerprint, String status) {
    }

    /**
     * 计划明细行。
     */
    public record PlanDetailRow(long outletId, String outletCode,
                                long baseM3, long carryoverInM3, long plannedM3) {
    }

    private static final RowMapper<PlanVersionRow> VERSION_MAPPER = (rs, n) ->
            new PlanVersionRow(rs.getLong("id"), rs.getLong("group_id"), rs.getInt("round_no"),
                    rs.getInt("version_no"), rs.getLong("total_water_m3"),
                    rs.getString("request_id"), rs.getString("fingerprint"), rs.getString("status"));

    private static final RowMapper<PlanDetailRow> DETAIL_MAPPER = (rs, n) ->
            new PlanDetailRow(rs.getLong("outlet_id"), rs.getString("outlet_code"),
                    rs.getLong("base_m3"), rs.getLong("carryover_in_m3"), rs.getLong("planned_m3"));

    private static final String VERSION_COLUMNS =
            "id, group_id, round_no, version_no, total_water_m3, request_id, fingerprint, status";

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PlanVersionRow> findVersionByRequestId(String requestId) {
        List<PlanVersionRow> rows = jdbc.query("SELECT " + VERSION_COLUMNS
                + " FROM plan_version WHERE request_id = ?", VERSION_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public Optional<PlanVersionRow> findVersionById(long planVersionId) {
        List<PlanVersionRow> rows = jdbc.query("SELECT " + VERSION_COLUMNS
                + " FROM plan_version WHERE id = ?", VERSION_MAPPER, planVersionId);
        return rows.stream().findFirst();
    }

    public List<PlanVersionRow> findVersionsByGroupAndRound(long groupId, int roundNo) {
        return jdbc.query("SELECT " + VERSION_COLUMNS
                        + " FROM plan_version WHERE group_id = ? AND round_no = ? ORDER BY version_no",
                VERSION_MAPPER, groupId, roundNo);
    }

    public int findMaxVersionNo(long groupId, int roundNo) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version_no) FROM plan_version WHERE group_id = ? AND round_no = ?",
                Integer.class, groupId, roundNo);
        return max == null ? 0 : max;
    }

    public long insertVersion(long groupId, int roundNo, int versionNo, long totalWaterM3,
                              String requestId, String fingerprint, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO plan_version (group_id, round_no, version_no, total_water_m3,"
                            + " request_id, fingerprint, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, groupId);
            ps.setInt(2, roundNo);
            ps.setInt(3, versionNo);
            ps.setLong(4, totalWaterM3);
            ps.setString(5, requestId);
            ps.setString(6, fingerprint);
            ps.setString(7, status);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public void insertDetail(long planVersionId, long outletId, String outletCode,
                             long baseM3, long carryoverInM3, long plannedM3) {
        jdbc.update("INSERT INTO plan_detail (plan_version_id, outlet_id, outlet_code,"
                        + " base_m3, carryover_in_m3, planned_m3) VALUES (?, ?, ?, ?, ?, ?)",
                planVersionId, outletId, outletCode, baseM3, carryoverInM3, plannedM3);
    }

    public List<PlanDetailRow> findDetailsByVersionId(long planVersionId) {
        return jdbc.query("SELECT outlet_id, outlet_code, base_m3, carryover_in_m3, planned_m3"
                + " FROM plan_detail WHERE plan_version_id = ? ORDER BY outlet_code", DETAIL_MAPPER, planVersionId);
    }

    public void updateVersionStatus(long planVersionId, String status) {
        jdbc.update("UPDATE plan_version SET status = ? WHERE id = ?", status, planVersionId);
    }

    /**
     * 读取当前生效指针（不加锁）。
     */
    public Optional<Long> findEffectivePlanVersionId(long groupId, int roundNo) {
        List<Long> rows = jdbc.query("SELECT plan_version_id FROM round_effective_plan"
                + " WHERE group_id = ? AND round_no = ?", (rs, n) -> rs.getLong(1), groupId, roundNo);
        return rows.stream().findFirst();
    }

    /**
     * 以行锁读取生效指针，用于计量与封存互斥，保证结转以最后已确认计量快照为准。
     */
    public Optional<Long> lockEffectivePlanVersionId(long groupId, int roundNo) {
        List<Long> rows = jdbc.query("SELECT plan_version_id FROM round_effective_plan"
                        + " WHERE group_id = ? AND round_no = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), groupId, roundNo);
        return rows.stream().findFirst();
    }

    public void insertEffectivePointer(long groupId, int roundNo, long planVersionId) {
        jdbc.update("INSERT INTO round_effective_plan (group_id, round_no, plan_version_id)"
                + " VALUES (?, ?, ?)", groupId, roundNo, planVersionId);
    }

    /**
     * 乐观更新生效指针：仅当当前指针仍为 {@code expectedOldId} 时生效，返回更新行数。
     */
    public int updateEffectivePointer(long groupId, int roundNo, long newPlanVersionId, long expectedOldId) {
        return jdbc.update("UPDATE round_effective_plan SET plan_version_id = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE group_id = ? AND round_no = ? AND plan_version_id = ?",
                newPlanVersionId, groupId, roundNo, expectedOldId);
    }
}
