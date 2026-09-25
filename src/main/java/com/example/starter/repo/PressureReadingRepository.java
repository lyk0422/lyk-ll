package com.example.starter.repo;

import com.example.starter.web.dto.TestCurveResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 风压读数与迟到事件数据访问。
 */
@Repository
public class PressureReadingRepository {

    private final JdbcTemplate jdbc;

    public PressureReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录风压读数；late 为 TRUE 表示销记后迟到数据，不参与试验曲线。
     */
    public long insert(long jobId, int pressure, LocalDateTime recordedAt, boolean late, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pressure_reading (job_id, pressure, recorded_at, late, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, jobId);
            ps.setInt(2, pressure);
            ps.setTimestamp(3, Timestamp.valueOf(recordedAt));
            ps.setBoolean(4, late);
            ps.setString(5, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询参与试验曲线的风压读数（不含迟到数据），按采集时间排序。
     */
    public List<TestCurveResponse.ReadingPoint> findCurvePoints(long jobId) {
        return jdbc.query("SELECT pressure, recorded_at FROM pressure_reading"
                        + " WHERE job_id = ? AND late = FALSE ORDER BY recorded_at, id",
                (rs, n) -> new TestCurveResponse.ReadingPoint(
                        rs.getInt("pressure"), rs.getTimestamp("recorded_at").toLocalDateTime()),
                jobId);
    }

    /**
     * 记录销记后迟到事件。
     */
    public void insertLateEvent(long jobId, String eventType, String detail) {
        jdbc.update("INSERT INTO late_event (job_id, event_type, detail, created_at)"
                + " VALUES (?, ?, ?, CURRENT_TIMESTAMP)", jobId, eventType, detail);
    }

    /**
     * 查询作业的迟到事件条数（测试核验用）。
     */
    public int countLateEvents(long jobId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM late_event WHERE job_id = ?",
                Integer.class, jobId);
        return count == null ? 0 : count;
    }
}
