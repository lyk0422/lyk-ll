package com.example.starter.repo;

import com.example.starter.domain.TailReleaseType;
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
 * 列尾装置绑定历史数据访问；提前解绑与销记只置解除标记，绑定记录全部保留。
 */
@Repository
public class TailBindingRepository {

    private static final RowMapper<TailBindingRow> ROW_MAPPER = (rs, n) -> new TailBindingRow(
            rs.getLong("id"),
            rs.getLong("device_id"),
            rs.getString("device_no"),
            rs.getLong("job_id"),
            rs.getString("tail_car_no"),
            rs.getInt("consist_version"),
            rs.getTimestamp("bound_at").toInstant(),
            rs.getTimestamp("released_at") == null ? null : rs.getTimestamp("released_at").toInstant(),
            rs.getString("release_type") == null ? null
                    : TailReleaseType.valueOf(rs.getString("release_type")));

    private final JdbcTemplate jdbc;

    public TailBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增一条生效绑定，冻结尾车号与编组版本。 */
    public long insert(long deviceId, String deviceNo, long jobId, String tailCarNo,
                       int consistVersion, Instant boundAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tail_binding (device_id, device_no, job_id, tail_car_no, consist_version,"
                            + " bound_at, released_at, release_type, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, deviceId);
            ps.setString(2, deviceNo);
            ps.setLong(3, jobId);
            ps.setString(4, tailCarNo);
            ps.setInt(5, consistVersion);
            ps.setTimestamp(6, Timestamp.from(boundAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 查询装置当前生效绑定（未解除）。 */
    public Optional<TailBindingRow> findActiveByDeviceId(long deviceId) {
        return jdbc.query("SELECT * FROM tail_binding WHERE device_id = ? AND released_at IS NULL"
                        + " ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, deviceId).stream().findFirst();
    }

    /** 查询作业当前生效绑定（未解除）。 */
    public Optional<TailBindingRow> findActiveByJobId(long jobId) {
        return jdbc.query("SELECT * FROM tail_binding WHERE job_id = ? AND released_at IS NULL"
                        + " ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, jobId).stream().findFirst();
    }

    /** 查询作业当前生效绑定并加行锁（发车确认与发车前解绑/摘挂竞争串行化）。 */
    public Optional<TailBindingRow> lockActiveByJobId(long jobId) {
        return jdbc.query("SELECT * FROM tail_binding WHERE job_id = ? AND released_at IS NULL"
                        + " ORDER BY id DESC LIMIT 1 FOR UPDATE",
                ROW_MAPPER, jobId).stream().findFirst();
    }

    /** 按主键查询绑定并加行锁。 */
    public Optional<TailBindingRow> lockById(long id) {
        return jdbc.query("SELECT * FROM tail_binding WHERE id = ? FOR UPDATE",
                ROW_MAPPER, id).stream().findFirst();
    }

    /** 按主键查询绑定。 */
    public Optional<TailBindingRow> findById(long id) {
        return jdbc.query("SELECT * FROM tail_binding WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /** 查询装置全部绑定历史（按ID升序，含已解除）。 */
    public List<TailBindingRow> findByDeviceId(long deviceId) {
        return jdbc.query("SELECT * FROM tail_binding WHERE device_id = ? ORDER BY id",
                ROW_MAPPER, deviceId);
    }

    /** 作业是否存在任何绑定记录（含已解除）：区分“从未启用列尾控制”与“绑定后已解绑”。 */
    public boolean existsByJobId(long jobId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tail_binding WHERE job_id = ?",
                Integer.class, jobId);
        return count != null && count > 0;
    }

    /**
     * 解除生效绑定；条件含 released_at IS NULL，并发解除仅一条命中。
     *
     * @return 命中条数（0 表示已被解除或绑定不存在）
     */
    public int release(long bindingId, Instant releasedAt, TailReleaseType releaseType) {
        return jdbc.update("UPDATE tail_binding SET released_at = ?, release_type = ?"
                        + " WHERE id = ? AND released_at IS NULL",
                Timestamp.from(releasedAt), releaseType.name(), bindingId);
    }
}
