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
 * 途中试验与列尾一致性双人确认数据访问。
 */
@Repository
public class EnRouteConfirmationRepository {

    private static final RowMapper<EnRouteConfirmationRow> ROW_MAPPER = (rs, n) -> new EnRouteConfirmationRow(
            rs.getLong("id"),
            rs.getLong("disposition_id"),
            rs.getLong("job_id"),
            rs.getInt("consist_version"),
            rs.getString("tail_car_no"),
            rs.getString("confirmer1"),
            rs.getString("confirmer2"),
            rs.getBoolean("valid"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EnRouteConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条有效双人确认。
     */
    public long insert(long dispositionId, long jobId, int consistVersion, String tailCarNo,
                       String confirmer1, String confirmer2, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO en_route_confirmation (disposition_id, job_id, consist_version, tail_car_no,"
                            + " confirmer1, confirmer2, valid, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, CURRENT_TIMESTAMP)",
                    new String[]{"id"});
            ps.setLong(1, dispositionId);
            ps.setLong(2, jobId);
            ps.setInt(3, consistVersion);
            ps.setString(4, tailCarNo);
            ps.setString(5, confirmer1);
            ps.setString(6, confirmer2);
            ps.setString(7, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询处置单下当前有效确认（至多一条）。
     */
    public Optional<EnRouteConfirmationRow> findValidByDispositionId(long dispositionId) {
        return jdbc.query("SELECT * FROM en_route_confirmation WHERE disposition_id = ? AND valid = TRUE"
                        + " ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, dispositionId).stream().findFirst();
    }

    /**
     * 查询处置单下全部确认证据（含已失效，按时间正序）。
     */
    public List<EnRouteConfirmationRow> findByDispositionId(long dispositionId) {
        return jdbc.query("SELECT * FROM en_route_confirmation WHERE disposition_id = ? ORDER BY id",
                ROW_MAPPER, dispositionId);
    }

    /**
     * 失效处置单下全部仍有效的确认（途中摘挂/重新确认同事务调用）；返回失效条数。
     */
    public int invalidateByDispositionId(long dispositionId) {
        return jdbc.update("UPDATE en_route_confirmation SET valid = FALSE"
                + " WHERE disposition_id = ? AND valid = TRUE", dispositionId);
    }
}
