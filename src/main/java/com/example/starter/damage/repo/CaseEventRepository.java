package com.example.starter.damage.repo;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.damage.repo.po.CaseEventPo;

/** 案件事件流水数据访问（结案后迟到检验照片等只记事件）。 */
@Repository
public class CaseEventRepository {

    private final JdbcTemplate jdbc;

    public CaseEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long caseId, String eventType, String payload, long nowUtc) {
        jdbc.update("INSERT INTO case_event (case_id, event_type, payload, created_at_utc) VALUES (?, ?, ?, ?)",
                caseId, eventType, payload, nowUtc);
    }

    public List<CaseEventPo> findByCaseId(long caseId) {
        return jdbc.query("""
                SELECT id, case_id, event_type, payload, created_at_utc
                FROM case_event WHERE case_id = ? ORDER BY id
                """, (rs, n) -> new CaseEventPo(
                rs.getLong("id"),
                rs.getLong("case_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                (Long) rs.getObject("created_at_utc")), caseId);
    }
}
