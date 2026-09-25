package com.example.starter.transition.repo;

import com.example.starter.transition.dto.RollView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 改判链事件仓储：强制降级、复议改判/驳回全程留痕。
 */
@Repository
public class VerdictEventRepository {

    private static final RowMapper<RollView.VerdictEventView> VIEW_MAPPER = (rs, n) ->
            new RollView.VerdictEventView(
                    rs.getString("event_type"),
                    rs.getString("from_verdict"),
                    rs.getString("to_verdict"),
                    rs.getString("request_id"),
                    rs.getTimestamp("event_time").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public VerdictEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String rollNo, String eventType, String fromVerdict, String toVerdict,
                       String requestId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO roll_verdict_event
                            (roll_no, event_type, from_verdict, to_verdict, request_id, event_time)
                        VALUES (?,?,?,?,?,?)
                        """,
                rollNo, eventType, fromVerdict, toVerdict, requestId, Timestamp.valueOf(now));
    }

    public List<RollView.VerdictEventView> findByRollNo(String rollNo) {
        return jdbc.query(
                "SELECT * FROM roll_verdict_event WHERE roll_no = ? ORDER BY id",
                VIEW_MAPPER, rollNo);
    }
}
