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
 * 途中新试验与列尾一致性双人确认数据访问。
 */
@Repository
public class EnRouteConfirmationRepository {

    private static final RowMapper<EnRouteConfirmationRow> ROW_MAPPER = (rs, n) -> new EnRouteConfirmationRow(
            rs.getLong("id"),
            rs.getLong("incident_id"),
            rs.getLong("job_id"),
            rs.getString("tail_car_no"),
            rs.getInt("consist_version"),
            rs.getString("confirmer1"),
            rs.getString("confirmer2"),
            rs.getBoolean("valid"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public EnRouteConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条有效途中双人确认。
     */
    public long insert(long incidentId, long jobId, String tailCarNo, int consistVersion,
                       String confirmer1, String confirmer2, String requestId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO en_route_confirmation (incident_id, job_id, tail_car_no, consist_version,"
                            + " confirmer1, confirmer2, valid, request_id, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, incidentId);
            ps.setLong(2, jobId);
            ps.setString(3, tailCarNo);
            ps.setInt(4, consistVersion);
            ps.setString(5, confirmer1);
            ps.setString(6, confirmer2);
            ps.setString(7, requestId);
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询处置单下当前有效确认（至多一条）。
     */
    public Optional<EnRouteConfirmationRow> findValidByIncidentId(long incidentId) {
        return jdbc.query("SELECT * FROM en_route_confirmation WHERE incident_id = ? AND valid = TRUE"
                        + " ORDER BY id DESC LIMIT 1",
                ROW_MAPPER, incidentId).stream().findFirst();
    }

    /**
     * 查询处置单全部确认证据（含已失效），按时间正序。
     */
    public List<EnRouteConfirmationRow> findAllByIncidentId(long incidentId) {
        return jdbc.query("SELECT * FROM en_route_confirmation WHERE incident_id = ? ORDER BY id",
                ROW_MAPPER, incidentId);
    }

    /**
     * 失效处置单全部仍有效的确认（阻断中编组变更或再次确认同事务调用）；返回失效条数。
     */
    public int invalidateByIncidentId(long incidentId) {
        return jdbc.update("UPDATE en_route_confirmation SET valid = FALSE"
                + " WHERE incident_id = ? AND valid = TRUE", incidentId);
    }
}
