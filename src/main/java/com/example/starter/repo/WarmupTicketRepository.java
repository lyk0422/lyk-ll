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

import com.example.starter.domain.WarmupTicket;
import com.example.starter.domain.WarmupTicketStatus;

/**
 * 升温操作票主表与窑升温占用槽访问。kiln_active_warmup 主键唯一约束保证
 * 同一窑同时刻只允许一张执行中升温票。
 */
@Repository
public class WarmupTicketRepository {

    static final String COLUMNS = """
            id, request_id, ticket_no, kiln_id, curve_version, status,
            current_stage_seq, created_at, completed_at
            """;

    private final RowMapper<WarmupTicket> mapper = (ResultSet rs, int n) -> new WarmupTicket(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("ticket_no"),
            rs.getString("kiln_id"),
            rs.getString("curve_version"),
            WarmupTicketStatus.valueOf(rs.getString("status")),
            rs.getInt("current_stage_seq"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("completed_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public WarmupTicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入升温票并回填自增主键。 */
    public long insert(WarmupTicket ticket) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO warmup_ticket (request_id, ticket_no, kiln_id, curve_version, status,
                        current_stage_seq, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setString(1, ticket.requestId());
            ps.setString(2, ticket.ticketNo());
            ps.setString(3, ticket.kilnId());
            ps.setString(4, ticket.curveVersion());
            ps.setString(5, ticket.status().name());
            ps.setInt(6, ticket.currentStageSeq());
            ps.setTimestamp(7, Timestamp.valueOf(ticket.createdAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public WarmupTicket findById(long id) {
        return queryOne("SELECT " + COLUMNS + " FROM warmup_ticket WHERE id = ?", id);
    }

    public WarmupTicket findByTicketNo(String ticketNo) {
        return queryOne("SELECT " + COLUMNS + " FROM warmup_ticket WHERE ticket_no = ?", ticketNo);
    }

    /** 查询窑当前执行中（曲线未走完）的升温票，用于投料同事务校验。 */
    public WarmupTicket findExecutingByKiln(String kilnId) {
        return queryOne("SELECT " + COLUMNS
                + " FROM warmup_ticket WHERE kiln_id = ? AND status = 'EXECUTING'", kilnId);
    }

    private WarmupTicket queryOne(String sql, Object... args) {
        List<WarmupTicket> list = jdbc.query(sql, mapper, args);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 阶段推进：更新当前阶段序号（与阶段行更新在同一事务内原子完成）。 */
    public int advanceStage(long id, int nextSeq) {
        return jdbc.update("""
                UPDATE warmup_ticket SET current_stage_seq = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'EXECUTING'
                """, nextSeq, id);
    }

    /** 曲线走完：置 COMPLETED 终态。 */
    public int markCompleted(long id, LocalDateTime completedAt) {
        return jdbc.update("""
                UPDATE warmup_ticket SET status = 'COMPLETED', completed_at = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'EXECUTING'
                """, Timestamp.valueOf(completedAt), id);
    }

    /**
     * 尝试占用窑升温票槽。命中主键唯一约束返回 false（同窑已有执行中升温票）。
     */
    public boolean tryAcquireKiln(String kilnId, long ticketId, LocalDateTime acquiredAt) {
        try {
            jdbc.update("INSERT INTO kiln_active_warmup (kiln_id, ticket_id, acquired_at) VALUES (?, ?, ?)",
                    kilnId, ticketId, Timestamp.valueOf(acquiredAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 曲线走完（或票终结）后释放窑升温票槽。 */
    public void releaseKiln(String kilnId) {
        jdbc.update("DELETE FROM kiln_active_warmup WHERE kiln_id = ?", kilnId);
    }
}
