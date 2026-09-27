package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 列尾一致性双人确认数据访问。
 */
@Repository
public class TailConfirmationRepository {

    private static final RowMapper<TailConfirmationRow> ROW_MAPPER = (rs, n) -> new TailConfirmationRow(
            rs.getLong("id"),
            rs.getLong("binding_id"),
            rs.getLong("job_id"),
            rs.getInt("consist_version"),
            rs.getString("tail_car_no"),
            rs.getString("confirmer"),
            rs.getBoolean("valid"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public TailConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一名确认人的一致性确认（初始有效）。
     */
    public long insert(long bindingId, long jobId, int consistVersion, String tailCarNo,
                       String confirmer, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tail_consistency_confirmation (binding_id, job_id, consist_version,"
                            + " tail_car_no, confirmer, valid, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, TRUE, ?, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, bindingId);
            ps.setLong(2, jobId);
            ps.setInt(3, consistVersion);
            ps.setString(4, tailCarNo);
            ps.setString(5, confirmer);
            ps.setString(6, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询指定绑定下指定编组版本的有效确认，按确认时间排序。
     */
    public List<TailConfirmationRow> findValidByBindingAndVersion(long bindingId, int consistVersion) {
        return jdbc.query("SELECT * FROM tail_consistency_confirmation"
                        + " WHERE binding_id = ? AND consist_version = ? AND valid = TRUE ORDER BY id",
                ROW_MAPPER, bindingId, consistVersion);
    }

    /**
     * 查询指定绑定下全部确认记录（证据查询用），按确认时间排序。
     */
    public List<TailConfirmationRow> findByBindingId(long bindingId) {
        return jdbc.query("SELECT * FROM tail_consistency_confirmation WHERE binding_id = ? ORDER BY id",
                ROW_MAPPER, bindingId);
    }

    /**
     * 判断指定绑定下指定编组版本中该确认人是否已有有效确认。
     */
    public boolean existsValidConfirmer(long bindingId, int consistVersion, String confirmer) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tail_consistency_confirmation"
                        + " WHERE binding_id = ? AND consist_version = ? AND confirmer = ? AND valid = TRUE",
                Integer.class, bindingId, consistVersion, confirmer);
        return count != null && count > 0;
    }

    /**
     * 作废作业全部有效确认（摘挂变更或解绑同事务调用）；返回作废条数。
     */
    public int invalidateAllByJobId(long jobId) {
        return jdbc.update("UPDATE tail_consistency_confirmation SET valid = FALSE"
                + " WHERE job_id = ? AND valid = TRUE", jobId);
    }
}
