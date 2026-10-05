package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.WarmUpStage;
import com.example.starter.domain.WarmUpStageType;

/**
 * 升温阶段、双人复核确认与保温超时告警访问。
 */
@Repository
public class WarmUpStageRepository {

    private static final String COLUMNS = """
            id, ticket_id, stage_no, stage_type, curve_stage_no, curve_version, target_temp,
            max_duration_minutes, started_at, confirmed_at, confirm_operator_a, confirm_operator_b
            """;

    private static final RowMapper<WarmUpStage> MAPPER = (ResultSet rs, int n) -> {
        long maxDuration = rs.getLong("max_duration_minutes");
        return new WarmUpStage(
                rs.getLong("id"),
                rs.getLong("ticket_id"),
                rs.getInt("stage_no"),
                WarmUpStageType.valueOf(rs.getString("stage_type")),
                rs.getInt("curve_stage_no"),
                rs.getString("curve_version"),
                rs.getBigDecimal("target_temp"),
                rs.wasNull() ? null : maxDuration,
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("confirmed_at", LocalDateTime.class),
                rs.getString("confirm_operator_a"),
                rs.getString("confirm_operator_b"));
    };

    private final JdbcTemplate jdbc;

    public WarmUpStageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入阶段记录（与票的阶段推进同事务），回填自增主键。 */
    public long insertStage(WarmUpStage stage) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO warm_up_stage
                        (ticket_id, stage_no, stage_type, curve_stage_no, curve_version, target_temp,
                         max_duration_minutes, started_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, stage.ticketId());
            ps.setInt(2, stage.stageNo());
            ps.setString(3, stage.stageType().name());
            ps.setInt(4, stage.curveStageNo());
            ps.setString(5, stage.curveVersion());
            ps.setBigDecimal(6, stage.targetTemp());
            if (stage.maxDurationMinutes() == null) {
                ps.setObject(7, null);
            } else {
                ps.setLong(7, stage.maxDurationMinutes());
            }
            ps.setTimestamp(8, Timestamp.valueOf(stage.startedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public WarmUpStage findById(long id) {
        List<WarmUpStage> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM warm_up_stage WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查票当前阶段（阶段序号最大的一条）。 */
    public WarmUpStage findCurrentStage(long ticketId) {
        List<WarmUpStage> list = jdbc.query("SELECT " + COLUMNS + " FROM warm_up_stage "
                + "WHERE ticket_id = ? ORDER BY stage_no DESC LIMIT 1", MAPPER, ticketId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<WarmUpStage> findByTicket(long ticketId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM warm_up_stage WHERE ticket_id = ? "
                + "ORDER BY stage_no", MAPPER, ticketId);
    }

    /** 双人复核齐全后回填阶段确认结果。 */
    public int markConfirmed(long stageId, LocalDateTime confirmedAt, String operatorA, String operatorB) {
        return jdbc.update("""
                UPDATE warm_up_stage
                SET confirmed_at = ?, confirm_operator_a = ?, confirm_operator_b = ?
                WHERE id = ? AND confirmed_at IS NULL
                """, Timestamp.valueOf(confirmedAt), operatorA, operatorB, stageId);
    }

    /** 登记一名操作人的阶段确认；同人同阶段重复登记返回 false。 */
    public boolean insertConfirm(long ticketId, int stageNo, String operator, LocalDateTime confirmedAt) {
        try {
            jdbc.update("""
                    INSERT INTO warm_up_stage_confirm (ticket_id, stage_no, operator, confirmed_at)
                    VALUES (?, ?, ?, ?)
                    """, ticketId, stageNo, operator, Timestamp.valueOf(confirmedAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 查阶段已登记确认人（按登记时刻升序）。 */
    public List<String> findConfirmOperators(long ticketId, int stageNo) {
        return jdbc.queryForList("""
                SELECT operator FROM warm_up_stage_confirm
                WHERE ticket_id = ? AND stage_no = ? ORDER BY confirmed_at, id
                """, String.class, ticketId, stageNo);
    }

    /** 保温超时升级告警，每票每阶段至多一条。 */
    public int insertAlarmIfAbsent(long ticketId, int stageNo, String alarmType, LocalDateTime triggeredAt) {
        return jdbc.update("""
                INSERT INTO warm_up_alarm (ticket_id, stage_no, alarm_type, triggered_at)
                SELECT ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM warm_up_alarm WHERE ticket_id = ? AND stage_no = ?)
                """, ticketId, stageNo, alarmType, Timestamp.valueOf(triggeredAt), ticketId, stageNo);
    }

    public int countAlarms(long ticketId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM warm_up_alarm WHERE ticket_id = ?", Integer.class, ticketId);
        return count == null ? 0 : count;
    }
}
