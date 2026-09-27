package com.example.starter.repo;

import com.example.starter.domain.EotBindingStatus;
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
 * 列尾绑定历史数据访问；active_device_id/active_job_id 上的唯一约束保证
 * 一台装置、一个作业同时仅有一条生效绑定，并发绑定唯一成功。
 */
@Repository
public class EotBindingRepository {

    private static final RowMapper<EotBindingRow> ROW_MAPPER = (rs, n) -> new EotBindingRow(
            rs.getLong("id"),
            rs.getLong("device_id"),
            rs.getString("device_no"),
            rs.getLong("job_id"),
            rs.getString("tail_car_no"),
            rs.getInt("consist_version"),
            EotBindingStatus.valueOf(rs.getString("status")),
            rs.getObject("active_device_id", Long.class),
            rs.getObject("active_job_id", Long.class),
            rs.getString("request_id"),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EotBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入生效绑定（active_device_id/active_job_id 唯一约束兜底并发唯一性）。
     */
    public long insertActive(long deviceId, String deviceNo, long jobId, String tailCarNo,
                             int consistVersion, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO eot_binding (device_id, device_no, job_id, tail_car_no, consist_version,"
                            + " status, active_device_id, active_job_id, request_id, released_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, NULL, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, deviceId);
            ps.setString(2, deviceNo);
            ps.setLong(3, jobId);
            ps.setString(4, tailCarNo);
            ps.setInt(5, consistVersion);
            ps.setLong(6, deviceId);
            ps.setLong(7, jobId);
            ps.setString(8, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询装置当前生效绑定。
     */
    public Optional<EotBindingRow> findActiveByDeviceId(long deviceId) {
        return jdbc.query("SELECT * FROM eot_binding WHERE active_device_id = ?", ROW_MAPPER, deviceId)
                .stream().findFirst();
    }

    /**
     * 查询作业当前生效绑定。
     */
    public Optional<EotBindingRow> findActiveByJobId(long jobId) {
        return jdbc.query("SELECT * FROM eot_binding WHERE active_job_id = ?", ROW_MAPPER, jobId)
                .stream().findFirst();
    }

    /**
     * 按主键查询绑定（含已解除历史）。
     */
    public Optional<EotBindingRow> findById(long bindingId) {
        return jdbc.query("SELECT * FROM eot_binding WHERE id = ?", ROW_MAPPER, bindingId)
                .stream().findFirst();
    }

    /**
     * 查询装置全部绑定历史（按时间正序）。
     */
    public List<EotBindingRow> findAllByDeviceId(long deviceId) {
        return jdbc.query("SELECT * FROM eot_binding WHERE device_id = ? ORDER BY id", ROW_MAPPER, deviceId);
    }

    /**
     * 查询作业全部绑定历史（含已解除，按时间正序）。
     */
    public List<EotBindingRow> findAllByJobId(long jobId) {
        return jdbc.query("SELECT * FROM eot_binding WHERE job_id = ? ORDER BY id", ROW_MAPPER, jobId);
    }

    /**
     * 原子解除生效绑定：置 RELEASED 并清空两个活跃键，供提前解绑或销记调用。
     *
     * @return 是否命中生效绑定
     */
    public boolean release(long bindingId, Instant releasedAt) {
        return jdbc.update("UPDATE eot_binding SET status = 'RELEASED', active_device_id = NULL,"
                        + " active_job_id = NULL, released_at = ? WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(releasedAt), bindingId) > 0;
    }
}
