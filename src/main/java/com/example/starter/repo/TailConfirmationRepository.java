package com.example.starter.repo;

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
 * 列尾装置一致性共同确认数据访问；失效仅置标记与原因，历史全部保留。
 */
@Repository
public class TailConfirmationRepository {

    private static final RowMapper<TailConfirmationRow> ROW_MAPPER = (rs, n) -> new TailConfirmationRow(
            rs.getLong("id"),
            rs.getLong("binding_id"),
            rs.getLong("job_id"),
            rs.getLong("device_id"),
            rs.getString("tail_car_no"),
            rs.getInt("consist_version"),
            rs.getString("confirmer1"),
            rs.getString("confirmer2"),
            rs.getTimestamp("confirmed_at").toInstant(),
            rs.getBoolean("valid"),
            rs.getTimestamp("invalidated_at") == null ? null : rs.getTimestamp("invalidated_at").toInstant(),
            rs.getString("invalidated_reason"));

    private final JdbcTemplate jdbc;

    public TailConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增一条有效共同确认。 */
    public long insert(long bindingId, long jobId, long deviceId, String tailCarNo, int consistVersion,
                       String confirmer1, String confirmer2, Instant confirmedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tail_confirmation (binding_id, job_id, device_id, tail_car_no, consist_version,"
                            + " confirmer1, confirmer2, confirmed_at, valid, invalidated_at,"
                            + " invalidated_reason, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, NULL, NULL, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, bindingId);
            ps.setLong(2, jobId);
            ps.setLong(3, deviceId);
            ps.setString(4, tailCarNo);
            ps.setInt(5, consistVersion);
            ps.setString(6, confirmer1);
            ps.setString(7, confirmer2);
            ps.setTimestamp(8, Timestamp.from(confirmedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 查询绑定下全部确认（按ID升序，含已失效）。 */
    public List<TailConfirmationRow> findByBindingId(long bindingId) {
        return jdbc.query("SELECT * FROM tail_confirmation WHERE binding_id = ? ORDER BY id",
                ROW_MAPPER, bindingId);
    }

    /** 查询作业当前有效共同确认（最新一条）。 */
    public Optional<TailConfirmationRow> findValidByJobId(long jobId) {
        return jdbc.query("SELECT * FROM tail_confirmation WHERE job_id = ? AND valid = TRUE"
                        + " ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, jobId).stream().findFirst();
    }

    /**
     * 将绑定下仍有效的确认置失效（摘挂改版本/尾车、提前解绑、销记）。
     *
     * @return 失效条数
     */
    public int invalidateByBinding(long bindingId, Instant invalidatedAt, String reason) {
        return jdbc.update("UPDATE tail_confirmation SET valid = FALSE, invalidated_at = ?,"
                        + " invalidated_reason = ? WHERE binding_id = ? AND valid = TRUE",
                Timestamp.from(invalidatedAt), reason, bindingId);
    }

    /**
     * 将同绑定下除指定确认外的其他有效确认置失效（同一作业重复共同确认时旧确认被取代）。
     *
     * @return 失效条数
     */
    public int invalidateOthersInBinding(long bindingId, long keepConfirmationId,
                                         Instant invalidatedAt, String reason) {
        return jdbc.update("UPDATE tail_confirmation SET valid = FALSE, invalidated_at = ?,"
                        + " invalidated_reason = ? WHERE binding_id = ? AND valid = TRUE AND id <> ?",
                Timestamp.from(invalidatedAt), reason, bindingId, keepConfirmationId);
    }
}
