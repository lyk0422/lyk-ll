package com.example.starter.repo;

import com.example.starter.domain.DispositionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;

/**
 * 途中异常处置单数据访问；active_job_id 唯一约束保证同作业仅一条活动处置，并发登记唯一成功。
 */
@Repository
public class EnRouteDispositionRepository {

    private static final RowMapper<DispositionRow> ROW_MAPPER = (rs, n) -> new DispositionRow(
            rs.getLong("id"),
            rs.getLong("job_id"),
            DispositionStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getInt("consist_version"),
            rs.getString("frozen_tail_car_no"),
            rs.getObject("frozen_eot_binding_id", Long.class),
            rs.getString("reason"),
            rs.getObject("active_job_id", Long.class),
            rs.getTimestamp("resumed_at") == null ? null : rs.getTimestamp("resumed_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EnRouteDispositionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记处置单：初始版本 1、ACTIVE 阻断中（active_job_id 唯一约束兜底并发唯一性）。
     */
    public long insert(long jobId, int consistVersion, String frozenTailCarNo, Long frozenEotBindingId,
                       String reason, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO en_route_disposition (job_id, status, version, consist_version,"
                            + " frozen_tail_car_no, frozen_eot_binding_id, reason, active_job_id, request_id,"
                            + " resumed_at, created_at, updated_at)"
                            + " VALUES (?, 'ACTIVE', 1, ?, ?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, jobId);
            ps.setInt(2, consistVersion);
            ps.setString(3, frozenTailCarNo);
            if (frozenEotBindingId == null) {
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setLong(4, frozenEotBindingId);
            }
            ps.setString(5, reason);
            ps.setLong(6, jobId);
            ps.setString(7, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询处置单。
     */
    public Optional<DispositionRow> findById(long id) {
        return jdbc.query("SELECT * FROM en_route_disposition WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询作业当前活动处置单（至多一条）。
     */
    public Optional<DispositionRow> findActiveByJobId(long jobId) {
        return jdbc.query("SELECT * FROM en_route_disposition WHERE active_job_id = ?", ROW_MAPPER, jobId)
                .stream().findFirst();
    }

    /**
     * 乐观条件递增处置版本（途中试验/确认/编组变更等修改用）：
     * 仅当处置单仍 ACTIVE 且版本等于期望版本时成功，版本加 1。
     *
     * @return 是否成功（false 表示已结束或版本不匹配）
     */
    public boolean bumpVersionIfActive(long dispositionId, int expectedVersion) {
        return jdbc.update("UPDATE en_route_disposition SET version = version + 1,"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'ACTIVE' AND version = ?",
                dispositionId, expectedVersion) > 0;
    }

    /**
     * 原子结束阻断：仅当处置单仍 ACTIVE 且版本等于期望版本时置 RESUMED、清空活动键并版本加 1。
     *
     * @return 是否成功（false 表示已结束或版本不匹配）
     */
    public boolean resumeIfActive(long dispositionId, int expectedVersion, Instant resumedAt) {
        return jdbc.update("UPDATE en_route_disposition SET status = 'RESUMED', active_job_id = NULL,"
                        + " version = version + 1, resumed_at = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'ACTIVE' AND version = ?",
                Timestamp.from(resumedAt), dispositionId, expectedVersion) > 0;
    }
}
