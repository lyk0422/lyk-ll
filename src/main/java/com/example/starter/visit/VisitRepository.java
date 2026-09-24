package com.example.starter.visit;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 访视数据访问。
 */
@Repository
public class VisitRepository {

    private final JdbcTemplate jdbc;

    public VisitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long trialId, VisitView visit, java.time.Instant createdAt) {
        jdbc.update(
                "INSERT INTO visit_record (trial_id, screening_no, visit_time, note, excluded_from_pas, created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                trialId, visit.screeningNo(), Timestamp.from(visit.visitTime()), visit.note(),
                visit.excludedFromPas(), Timestamp.from(createdAt));
    }

    public List<VisitView> listByScreening(long trialId, String screeningNo) {
        return jdbc.query(
                "SELECT id, screening_no, visit_time, note, excluded_from_pas FROM visit_record"
                        + " WHERE trial_id = ? AND screening_no = ? ORDER BY id",
                (rs, rowNum) -> new VisitView(
                        rs.getLong("id"),
                        rs.getString("screening_no"),
                        rs.getTimestamp("visit_time").toInstant(),
                        rs.getString("note"),
                        rs.getBoolean("excluded_from_pas")),
                trialId, screeningNo);
    }
}
