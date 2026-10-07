package com.example.starter.repo;

import com.example.starter.domain.IncidentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 途中异常处置单数据访问；active_job_id 唯一约束保证同作业仅一条活动处置，并发登记唯一成功。
 */
@Repository
public class IncidentRepository {

    private static final RowMapper<IncidentRow> ROW_MAPPER = (rs, n) -> new IncidentRow(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getInt("consist_version"),
            rs.getObject("eot_binding_id", Long.class),
            rs.getString("reason"),
            IncidentStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getTimestamp("resumed_at") == null ? null : rs.getTimestamp("resumed_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public IncidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记活动处置（OPEN，版本 1）；active_job_id 唯一约束兜底并发唯一性。
     */
    public long insertOpen(long jobId, int consistVersion, Long eotBindingId, String reason,
                           String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Timestamp ts = Timestamp.from(now);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO incident (job_id, consist_version, eot_binding_id, reason, status, version,"
                            + " active_job_id, request_id, resumed_at, created_at)"
                            + " VALUES (?, ?, ?, ?, 'OPEN', 1, ?, ?, NULL, ?)",
                    new String[]{"id"});
            ps.setLong(1, jobId);
            ps.setInt(2, consistVersion);
            if (eotBindingId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, eotBindingId);
            }
            ps.setString(4, reason);
            ps.setLong(5, jobId);
            ps.setString(6, requestId);
            ps.setTimestamp(7, ts);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询处置单（含已续行历史）。
     */
    public Optional<IncidentRow> findById(long id) {
        return jdbc.query("SELECT * FROM incident WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询作业当前活动处置（至多一条）。
     */
    public Optional<IncidentRow> findActiveByJobId(long jobId) {
        return jdbc.query("SELECT * FROM incident WHERE active_job_id = ?", ROW_MAPPER, jobId)
                .stream().findFirst();
    }

    /**
     * 查询作业全部处置历史（含已续行，按时间正序）。
     */
    public List<IncidentRow> findAllByJobId(long jobId) {
        return jdbc.query("SELECT * FROM incident WHERE job_id = ? ORDER BY id", ROW_MAPPER, jobId);
    }

    /**
     * 乐观条件递增处置版本：仅当处置仍 OPEN 且版本等于期望版本时成功，版本加 1。
     *
     * @return 是否成功（false 表示已续行或版本不匹配）
     */
    public boolean bumpVersionIfOpen(long incidentId, int expectedVersion) {
        return jdbc.update("UPDATE incident SET version = version + 1"
                        + " WHERE id = ? AND status = 'OPEN' AND version = ?",
                incidentId, expectedVersion) > 0;
    }

    /**
     * 乐观条件续行：仅当处置仍 OPEN 且版本等于期望版本时置 RESUMED、清空活动键并版本加 1。
     *
     * @return 是否成功（false 表示已续行或版本不匹配）
     */
    public boolean resumeIfOpen(long incidentId, int expectedVersion, Instant resumedAt) {
        return jdbc.update("UPDATE incident SET status = 'RESUMED', active_job_id = NULL,"
                        + " version = version + 1, resumed_at = ?"
                        + " WHERE id = ? AND status = 'OPEN' AND version = ?",
                Timestamp.from(resumedAt), incidentId, expectedVersion) > 0;
    }
}
