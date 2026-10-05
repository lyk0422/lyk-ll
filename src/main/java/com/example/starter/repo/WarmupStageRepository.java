package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.WarmupStage;
import com.example.starter.domain.WarmupStageStatus;
import com.example.starter.domain.WarmupStageType;

/**
 * 升温曲线阶段访问。状态流转均用带前置状态的条件更新，保证并发下只生效一次。
 */
@Repository
public class WarmupStageRepository {

    static final String COLUMNS = """
            id, ticket_id, seq, curve_version, stage_type, target_temp, max_duration_minutes,
            status, entered_at, holding_since, confirm_user1, confirm_user2, confirmed_at,
            alarm, alarmed_at
            """;

    private final RowMapper<WarmupStage> mapper = (ResultSet rs, int n) -> new WarmupStage(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getInt("seq"),
            rs.getString("curve_version"),
            WarmupStageType.valueOf(rs.getString("stage_type")),
            rs.getBigDecimal("target_temp"),
            rs.getInt("max_duration_minutes"),
            WarmupStageStatus.valueOf(rs.getString("status")),
            rs.getObject("entered_at", LocalDateTime.class),
            rs.getObject("holding_since", LocalDateTime.class),
            rs.getString("confirm_user1"),
            rs.getString("confirm_user2"),
            rs.getObject("confirmed_at", LocalDateTime.class),
            rs.getBoolean("alarm"),
            rs.getObject("alarmed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public WarmupStageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入阶段定义；首段直接 ACTIVE 并记录进入时刻，其余 PENDING。 */
    public void insert(WarmupStage stage) {
        jdbc.update("""
                INSERT INTO warmup_stage (ticket_id, seq, curve_version, stage_type, target_temp,
                    max_duration_minutes, status, entered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                stage.ticketId(), stage.seq(), stage.curveVersion(), stage.stageType().name(),
                stage.targetTemp(), stage.maxDurationMinutes(), stage.status().name(),
                stage.enteredAt() == null ? null : Timestamp.valueOf(stage.enteredAt()));
    }

    public List<WarmupStage> findByTicket(long ticketId) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM warmup_stage WHERE ticket_id = ? ORDER BY seq", mapper, ticketId);
    }

    /** 查询票内当前执行中阶段。 */
    public WarmupStage findActiveByTicket(long ticketId) {
        var list = jdbc.query("SELECT " + COLUMNS
                + " FROM warmup_stage WHERE ticket_id = ? AND status = 'ACTIVE'", mapper, ticketId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 第一确认人落库：仅当阶段 ACTIVE 且尚无确认人时生效，并发下只成功一次。 */
    public int markFirstConfirm(long id, String operatorId) {
        return jdbc.update("""
                UPDATE warmup_stage SET confirm_user1 = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE' AND confirm_user1 IS NULL
                """, operatorId, id);
    }

    /** 第二确认人复核落库：阶段确认完成，须已有第一确认人且尚无第二确认人。 */
    public int markSecondConfirm(long id, String operatorId, LocalDateTime confirmedAt) {
        return jdbc.update("""
                UPDATE warmup_stage SET confirm_user2 = ?, confirmed_at = ?, status = 'CONFIRMED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE' AND confirm_user1 IS NOT NULL
                  AND confirm_user2 IS NULL
                """, operatorId, Timestamp.valueOf(confirmedAt), id);
    }

    /** 推进下一段进入 ACTIVE（与票内阶段序号更新同一事务原子完成）。 */
    public int activateStage(long ticketId, int seq, LocalDateTime enteredAt) {
        return jdbc.update("""
                UPDATE warmup_stage SET status = 'ACTIVE', entered_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE ticket_id = ? AND seq = ? AND status = 'PENDING'
                """, Timestamp.valueOf(enteredAt), ticketId, seq);
    }

    /** 恰满最长用时未确认的升温段（entered_at + 最长用时 &lt;= now）。 */
    public List<WarmupStage> findHeatingExpired(LocalDateTime now) {
        return jdbc.query("SELECT " + COLUMNS + """
                FROM warmup_stage
                WHERE status = 'ACTIVE' AND stage_type = 'HEATING'
                  AND TIMESTAMPADD(MINUTE, max_duration_minutes, entered_at) <= ?
                """, mapper, Timestamp.valueOf(now));
    }

    /** 升温段自动转保温段：记录转入时刻，恰满最长用时判定。 */
    public int convertToHolding(long id, LocalDateTime holdingSince) {
        return jdbc.update("""
                UPDATE warmup_stage SET stage_type = 'HOLDING', holding_since = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ACTIVE' AND stage_type = 'HEATING'
                """, Timestamp.valueOf(holdingSince), id);
    }

    /** 保温段恰满 4 小时未告警的阶段（holding_since &lt;= 告警起算线）。 */
    public List<WarmupStage> findHoldingAlarmDue(LocalDateTime alarmDeadline) {
        return jdbc.query("SELECT " + COLUMNS + """
                FROM warmup_stage
                WHERE status = 'ACTIVE' AND stage_type = 'HOLDING' AND alarm = FALSE
                  AND holding_since <= ?
                """, mapper, Timestamp.valueOf(alarmDeadline));
    }

    /** 保温段超时升级告警。 */
    public int markAlarm(long id, LocalDateTime alarmedAt) {
        return jdbc.update("""
                UPDATE warmup_stage SET alarm = TRUE, alarmed_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND stage_type = 'HOLDING' AND alarm = FALSE
                """, Timestamp.valueOf(alarmedAt), id);
    }
}
