package com.example.starter.repo;

import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.ReleaseReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 列尾装置绑定历史数据访问；绑定解除后历史保留。
 */
@Repository
public class TailBindingRepository {

    private static final RowMapper<TailBindingRow> ROW_MAPPER = (rs, n) -> new TailBindingRow(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getLong("device_id"),
            rs.getString("device_no"),
            rs.getString("tail_car_no"),
            rs.getInt("consist_version"),
            BindingStatus.valueOf(rs.getString("status")),
            rs.getString("release_reason") == null ? null : ReleaseReason.valueOf(rs.getString("release_reason")),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TailBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录新绑定（生效中），冻结当前尾车与编组版本。
     */
    public long insert(long jobId, long deviceId, String deviceNo, String tailCarNo,
                       int consistVersion, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tail_binding (job_id, device_id, device_no, tail_car_no, consist_version,"
                            + " status, release_reason, request_id, created_at, released_at)"
                            + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', NULL, ?, CURRENT_TIMESTAMP, NULL)",
                    new String[]{"id"});
            ps.setLong(1, jobId);
            ps.setLong(2, deviceId);
            ps.setString(3, deviceNo);
            ps.setString(4, tailCarNo);
            ps.setInt(5, consistVersion);
            ps.setString(6, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询作业当前生效中的绑定。
     */
    public Optional<TailBindingRow> findActiveByJobId(long jobId) {
        return jdbc.query("SELECT * FROM tail_binding WHERE job_id = ? AND status = 'ACTIVE'"
                        + " ORDER BY id DESC LIMIT 1", ROW_MAPPER, jobId).stream().findFirst();
    }

    /**
     * 查询作业全部绑定历史（含已解除），按绑定时间排序。
     */
    public List<TailBindingRow> findByJobId(long jobId) {
        return jdbc.query("SELECT * FROM tail_binding WHERE job_id = ? ORDER BY id", ROW_MAPPER, jobId);
    }

    /**
     * 解除绑定（销记或提前解绑），保留历史；返回是否命中生效中绑定。
     */
    public boolean release(long bindingId, ReleaseReason reason) {
        return jdbc.update("UPDATE tail_binding SET status = 'RELEASED', release_reason = ?,"
                        + " released_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ACTIVE'",
                reason.name(), bindingId) > 0;
    }
}
