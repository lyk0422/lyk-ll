package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.CoFiringPlan;
import com.example.starter.domain.PlanStatus;

/**
 * 掺烧方案主表访问。
 */
@Repository
public class PlanRepository {

    static final String COLUMNS = """
            id, request_id, plan_no, kiln_id, license_id, substitution_rate, status,
            planned_start, planned_end, reject_reason, reject_category,
            submitted_at, reviewed_at, started_at, stopped_at, banned_at, sealed_at, sealed_date
            """;

    private final RowMapper<CoFiringPlan> mapper = (ResultSet rs, int n) -> new CoFiringPlan(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("plan_no"),
            rs.getString("kiln_id"),
            rs.getLong("license_id"),
            rs.getBigDecimal("substitution_rate"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getObject("planned_start", LocalDateTime.class),
            rs.getObject("planned_end", LocalDateTime.class),
            rs.getString("reject_reason"),
            rs.getString("reject_category"),
            rs.getObject("submitted_at", LocalDateTime.class),
            rs.getObject("reviewed_at", LocalDateTime.class),
            rs.getObject("started_at", LocalDateTime.class),
            rs.getObject("stopped_at", LocalDateTime.class),
            rs.getObject("banned_at", LocalDateTime.class),
            rs.getObject("sealed_at", LocalDateTime.class),
            rs.getObject("sealed_date", java.time.LocalDate.class));

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入新方案并回填自增主键。 */
    public long insert(CoFiringPlan plan) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO co_firing_plan (request_id, plan_no, kiln_id, license_id, substitution_rate,
                        status, planned_start, planned_end, reject_reason, reject_category, submitted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setString(1, plan.requestId());
            ps.setString(2, plan.planNo());
            ps.setString(3, plan.kilnId());
            if (plan.licenseId() == null) {
                ps.setObject(4, null);
            } else {
                ps.setLong(4, plan.licenseId());
            }
            ps.setBigDecimal(5, plan.substitutionRate());
            ps.setString(6, plan.status().name());
            ps.setTimestamp(7, Timestamp.valueOf(plan.plannedStart()));
            ps.setTimestamp(8, Timestamp.valueOf(plan.plannedEnd()));
            ps.setString(9, plan.rejectReason());
            ps.setString(10, plan.rejectCategory());
            ps.setTimestamp(11, plan.submittedAt() == null ? null : Timestamp.valueOf(plan.submittedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public CoFiringPlan findById(long id) {
        return queryOne("SELECT " + COLUMNS + " FROM co_firing_plan WHERE id = ?", id);
    }

    public CoFiringPlan findByPlanNo(String planNo) {
        return queryOne("SELECT " + COLUMNS + " FROM co_firing_plan WHERE plan_no = ?", planNo);
    }

    public CoFiringPlan findByRequestId(String requestId) {
        return queryOne("SELECT " + COLUMNS + " FROM co_firing_plan WHERE request_id = ?", requestId);
    }

    private CoFiringPlan queryOne(String sql, Object... args) {
        List<CoFiringPlan> list = jdbc.query(sql, mapper, args);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询窑当前执行中方案（不含已停用/禁烧/封存）。 */
    public CoFiringPlan findActiveByKiln(String kilnId) {
        return queryOne("SELECT " + COLUMNS + " FROM co_firing_plan WHERE kiln_id = ? AND status = 'EXECUTING'",
                kilnId);
    }

    /**
     * 查询同窑时段重叠且尚未终结的方案（APPROVED 待执行 / EXECUTING 执行中），
     * 用于申报阶段拦截并发受理。
     */
    public CoFiringPlan findBlocking(String kilnId, LocalDateTime start, LocalDateTime end) {
        return queryOne("SELECT " + COLUMNS + """
                FROM co_firing_plan
                WHERE kiln_id = ? AND status IN ('APPROVED', 'EXECUTING')
                  AND planned_start < ? AND planned_end > ?
                """, kilnId, Timestamp.valueOf(end), Timestamp.valueOf(start));
    }

    /** 更新审核结果（通过或整单拒绝）。 */
    public int updateReview(long id, PlanStatus status, String rejectCategory, String rejectReason,
                            LocalDateTime reviewedAt) {
        return jdbc.update("""
                UPDATE co_firing_plan SET status = ?, reject_category = ?, reject_reason = ?,
                    reviewed_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, status.name(), rejectCategory, rejectReason,
                reviewedAt == null ? null : Timestamp.valueOf(reviewedAt), id);
    }

    /** 状态机更新，带期望前置状态作为乐观条件。 */
    public int compareAndSetStatus(long id, PlanStatus expected, PlanStatus target) {
        return jdbc.update(
                "UPDATE co_firing_plan SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?",
                target.name(), id, expected.name());
    }

    public int markStarted(long id, LocalDateTime startedAt) {
        return jdbc.update(
                "UPDATE co_firing_plan SET status = 'EXECUTING', started_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'APPROVED'",
                Timestamp.valueOf(startedAt), id);
    }

    public int markStopped(long id, LocalDateTime stoppedAt) {
        return jdbc.update(
                "UPDATE co_firing_plan SET status = 'STOPPED', stopped_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'EXECUTING'",
                Timestamp.valueOf(stoppedAt), id);
    }

    public int markBanned(long id, LocalDateTime bannedAt) {
        return jdbc.update(
                "UPDATE co_firing_plan SET status = 'BANNED', banned_at = ?, stopped_at = COALESCE(stopped_at, ?), "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'EXECUTING'",
                Timestamp.valueOf(bannedAt), Timestamp.valueOf(bannedAt), id);
    }

    public int markSealed(long id, LocalDateTime sealedAt, java.time.LocalDate sealedDate) {
        return jdbc.update("""
                UPDATE co_firing_plan SET status = 'SEALED', sealed_at = ?, sealed_date = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status <> 'SEALED'
                """, Timestamp.valueOf(sealedAt), sealedDate, id);
    }
}
