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

import com.example.starter.domain.WarmUpTicket;
import com.example.starter.domain.WarmUpTicketStatus;

/**
 * 升温操作票与窑升温占用槽访问。kiln_active_warmup 主键唯一约束保证同窑同时刻
 * 只允许一张执行中/保温中升温票。
 */
@Repository
public class WarmUpTicketRepository {

    private static final String COLUMNS = """
            id, request_id, ticket_no, kiln_id, curve_version, status, current_stage_no,
            hold_started_at, created_at, completed_at
            """;

    private static final RowMapper<WarmUpTicket> MAPPER = (ResultSet rs, int n) -> new WarmUpTicket(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("ticket_no"),
            rs.getString("kiln_id"),
            rs.getString("curve_version"),
            WarmUpTicketStatus.valueOf(rs.getString("status")),
            rs.getInt("current_stage_no"),
            rs.getObject("hold_started_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("completed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public WarmUpTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入升温票并回填自增主键。 */
    public long insert(WarmUpTicket ticket) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO warm_up_ticket
                        (request_id, ticket_no, kiln_id, curve_version, status, current_stage_no, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setString(1, ticket.requestId());
            ps.setString(2, ticket.ticketNo());
            ps.setString(3, ticket.kilnId());
            ps.setString(4, ticket.curveVersion());
            ps.setString(5, ticket.status().name());
            ps.setInt(6, ticket.currentStageNo());
            ps.setTimestamp(7, Timestamp.valueOf(ticket.createdAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public WarmUpTicket findById(long id) {
        return queryOne("SELECT " + COLUMNS + " FROM warm_up_ticket WHERE id = ?", id);
    }

    public WarmUpTicket findByTicketNo(String ticketNo) {
        return queryOne("SELECT " + COLUMNS + " FROM warm_up_ticket WHERE ticket_no = ?", ticketNo);
    }

    /** 查窑最近一张升温票（按主键倒序），用于投料前校验曲线是否走完。 */
    public WarmUpTicket findLatestByKiln(String kilnId) {
        return queryOne("SELECT " + COLUMNS + " FROM warm_up_ticket WHERE kiln_id = ? "
                + "ORDER BY id DESC LIMIT 1", kilnId);
    }

    /** 对升温票加行锁，串行化同票并发阶段确认。 */
    public WarmUpTicket lockById(long id) {
        return queryOne("SELECT " + COLUMNS + " FROM warm_up_ticket WHERE id = ? FOR UPDATE", id);
    }

    /** 保温中且进入保温时刻不晚于 deadline 的票（恰满 4 小时即命中），供超时告警扫描。 */
    public List<WarmUpTicket> findHoldingOverdue(LocalDateTime deadline) {
        return jdbc.query("SELECT " + COLUMNS + " FROM warm_up_ticket "
                        + "WHERE status = 'HOLDING' AND hold_started_at <= ? ORDER BY id",
                MAPPER, Timestamp.valueOf(deadline));
    }

    /**
     * 阶段推进与状态迁移：带期望前置状态作为乐观条件，与阶段记录写入同事务提交。
     */
    public int updateProgress(long id, WarmUpTicketStatus expected, WarmUpTicketStatus target,
                              int currentStageNo, LocalDateTime holdStartedAt,
                              LocalDateTime completedAt) {
        return jdbc.update("""
                UPDATE warm_up_ticket
                SET status = ?, current_stage_no = ?, hold_started_at = ?, completed_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """, target.name(), currentStageNo,
                holdStartedAt == null ? null : Timestamp.valueOf(holdStartedAt),
                completedAt == null ? null : Timestamp.valueOf(completedAt),
                id, expected.name());
    }

    /** 尝试占用窑升温槽。命中主键唯一约束返回 false（同窑已有执行中/保温中升温票）。 */
    public boolean tryAcquireKiln(String kilnId, long ticketId, LocalDateTime acquiredAt) {
        try {
            jdbc.update("INSERT INTO kiln_active_warmup (kiln_id, ticket_id, acquired_at) VALUES (?, ?, ?)",
                    kilnId, ticketId, Timestamp.valueOf(acquiredAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void releaseKiln(String kilnId) {
        jdbc.update("DELETE FROM kiln_active_warmup WHERE kiln_id = ?", kilnId);
    }

    private WarmUpTicket queryOne(String sql, Object... args) {
        List<WarmUpTicket> list = jdbc.query(sql, MAPPER, args);
        return list.isEmpty() ? null : list.get(0);
    }
}
