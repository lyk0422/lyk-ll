package com.example.starter.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 审计事件数据访问：追加写入，按 id（发生顺序）查询。
 */
@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(long trialId, String screeningNo, String eventType, String actor,
            String detail, Instant occurredAt) {
        jdbc.update(
                "INSERT INTO audit_event (trial_id, screening_no, event_type, actor, detail, occurred_at)"
                        + " VALUES (?,?,?,?,?,?)",
                trialId, screeningNo, eventType, actor, detail, Timestamp.from(occurredAt));
    }

    /**
     * 按发生顺序（id 升序）查询某试验的审计事件；screeningNo 为 null 时查询全部受试者。
     */
    public List<AuditEventView> list(long trialId, String screeningNo) {
        String sql = "SELECT id, screening_no, event_type, actor, detail, occurred_at FROM audit_event"
                + " WHERE trial_id = ?" + (screeningNo == null ? "" : " AND screening_no = ?")
                + " ORDER BY id";
        Object[] args = screeningNo == null ? new Object[] {trialId} : new Object[] {trialId, screeningNo};
        return jdbc.query(sql, (rs, rowNum) -> new AuditEventView(
                rs.getLong("id"),
                rs.getString("screening_no"),
                rs.getString("event_type"),
                rs.getString("actor"),
                rs.getString("detail"),
                rs.getTimestamp("occurred_at").toInstant()), args);
    }
}
