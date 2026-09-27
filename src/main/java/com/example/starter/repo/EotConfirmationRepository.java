package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 列尾一致性双人确认数据访问。
 */
@Repository
public class EotConfirmationRepository {

    private static final RowMapper<EotConfirmationRow> ROW_MAPPER = (rs, n) -> new EotConfirmationRow(
            rs.getLong("id"),
            rs.getLong("binding_id"),
            rs.getLong("device_id"),
            rs.getString("device_no"),
            rs.getLong("job_id"),
            rs.getString("tail_car_no"),
            rs.getInt("consist_version"),
            rs.getString("confirmer1"),
            rs.getString("confirmer2"),
            rs.getBoolean("valid"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EotConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条有效双人确认。
     */
    public long insert(long bindingId, long deviceId, String deviceNo, long jobId,
                       String tailCarNo, int consistVersion, String confirmer1, String confirmer2,
                       String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO eot_consistency_confirmation (binding_id, device_id, device_no, job_id,"
                            + " tail_car_no, consist_version, confirmer1, confirmer2, valid, request_id,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, bindingId);
            ps.setLong(2, deviceId);
            ps.setString(3, deviceNo);
            ps.setLong(4, jobId);
            ps.setString(5, tailCarNo);
            ps.setInt(6, consistVersion);
            ps.setString(7, confirmer1);
            ps.setString(8, confirmer2);
            ps.setString(9, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询绑定下当前有效确认（至多一条）。
     */
    public Optional<EotConfirmationRow> findValidByBindingId(long bindingId) {
        return jdbc.query("SELECT * FROM eot_consistency_confirmation WHERE binding_id = ? AND valid = TRUE"
                        + " ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, bindingId).stream().findFirst();
    }

    /**
     * 查询绑定下全部确认证据（含已失效），按时间正序。
     */
    public List<EotConfirmationRow> findAllByBindingIds(List<Long> bindingIds) {
        if (bindingIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", bindingIds.stream().map(x -> "?").toList());
        return jdbc.query("SELECT * FROM eot_consistency_confirmation WHERE binding_id IN ("
                        + placeholders + ") ORDER BY id",
                ROW_MAPPER, bindingIds.toArray());
    }

    /**
     * 查询作业当前有效确认（至多一条）。
     */
    public Optional<EotConfirmationRow> findValidByJobId(long jobId) {
        return jdbc.query("SELECT * FROM eot_consistency_confirmation WHERE job_id = ? AND valid = TRUE"
                        + " ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, jobId).stream().findFirst();
    }

    /**
     * 失效作业全部仍有效的确认（摘挂/提前解绑/销记同事务调用）；返回失效条数。
     */
    public int invalidateByJobId(long jobId) {
        return jdbc.update("UPDATE eot_consistency_confirmation SET valid = FALSE"
                + " WHERE job_id = ? AND valid = TRUE", jobId);
    }

    /**
     * 失效指定绑定全部仍有效的确认；返回失效条数。
     */
    public int invalidateByBindingId(long bindingId) {
        return jdbc.update("UPDATE eot_consistency_confirmation SET valid = FALSE"
                + " WHERE binding_id = ? AND valid = TRUE", bindingId);
    }
}
