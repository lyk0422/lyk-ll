package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.PermitRow;
import com.example.starter.domain.PermitStatus;
import com.example.starter.domain.WorkType;

/**
 * 作业许可数据访问。同一路段同时刻仅一张执行中许可由
 * 唯一约束 uq_permit_executing(segment_id, executing_flag) 保证。
 */
@Repository
public class PermitRepository {

    private static final RowMapper<PermitRow> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public PermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static PermitRow map(ResultSet rs) throws SQLException {
        return new PermitRow(rs.getLong("id"), rs.getString("permit_no"), rs.getLong("segment_id"),
                rs.getLong("plan_id"), WorkType.valueOf(rs.getString("work_type")),
                PermitStatus.valueOf(rs.getString("status")),
                rs.getObject("deadline", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class));
    }

    public long insert(String permitNo, long segmentId, long planId, WorkType workType,
                       LocalDateTime deadline, LocalDateTime createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO work_permit(permit_no, segment_id, plan_id, work_type, status,"
                            + " executing_flag, deadline, created_at)"
                            + " VALUES (?, ?, ?, ?, 'EXECUTING', 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, permitNo);
            ps.setLong(2, segmentId);
            ps.setLong(3, planId);
            ps.setString(4, workType.name());
            ps.setObject(5, deadline);
            ps.setObject(6, createdAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<PermitRow> findByPermitNo(String permitNo) {
        return jdbc.query("SELECT * FROM work_permit WHERE permit_no = ?", MAPPER, permitNo)
                .stream().findFirst();
    }

    public Optional<PermitRow> findById(long id) {
        return jdbc.query("SELECT * FROM work_permit WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public void complete(long id, LocalDateTime completedAt) {
        jdbc.update("UPDATE work_permit SET status = 'COMPLETED', executing_flag = NULL,"
                + " completed_at = ? WHERE id = ?", completedAt, id);
    }

    public long countExecutingBySegment(long segmentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_permit WHERE segment_id = ? AND status = 'EXECUTING'",
                Long.class, segmentId);
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM work_permit", Long.class);
    }
}
