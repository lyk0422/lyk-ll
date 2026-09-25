package com.example.starter.hazmat.repo;

import com.example.starter.hazmat.domain.RerouteStatus;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 紧急改线申请仓储。
 */
@Repository
public class RerouteRepository {

    /**
     * 改线申请视图。
     */
    public record RerouteRow(long id, String requestId, String fingerprint, long waybillId,
                             long newRouteId, RerouteStatus status) {
    }

    private final JdbcTemplate jdbc;

    public RerouteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String requestId, String fingerprint, long waybillId, long newRouteId,
                       LocalDateTime now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO reroute_request (request_id, fingerprint, waybill_id,"
                            + " new_route_id, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, waybillId);
            ps.setLong(4, newRouteId);
            ps.setString(5, RerouteStatus.PENDING.name());
            ps.setTimestamp(6, Timestamp.valueOf(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<RerouteRow> findById(long id) {
        List<RerouteRow> rows = jdbc.query(
                "SELECT id, request_id, fingerprint, waybill_id, new_route_id, status"
                        + " FROM reroute_request WHERE id = ?",
                (rs, i) -> new RerouteRow(rs.getLong("id"), rs.getString("request_id"),
                        rs.getString("fingerprint"), rs.getLong("waybill_id"),
                        rs.getLong("new_route_id"), RerouteStatus.valueOf(rs.getString("status"))),
                id);
        return rows.stream().findFirst();
    }

    public Optional<RerouteRow> findByRequestId(String requestId) {
        List<RerouteRow> rows = jdbc.query(
                "SELECT id, request_id, fingerprint, waybill_id, new_route_id, status"
                        + " FROM reroute_request WHERE request_id = ?",
                (rs, i) -> new RerouteRow(rs.getLong("id"), rs.getString("request_id"),
                        rs.getString("fingerprint"), rs.getLong("waybill_id"),
                        rs.getLong("new_route_id"), RerouteStatus.valueOf(rs.getString("status"))),
                requestId);
        return rows.stream().findFirst();
    }

    /**
     * 原子核准：仅当仍 PENDING 时更新为 target，返回受影响行数。
     */
    public int transition(long id, RerouteStatus target, LocalDateTime now) {
        return jdbc.update(
                "UPDATE reroute_request SET status = ?, approved_at = ?"
                        + " WHERE id = ? AND status = 'PENDING'",
                target.name(), Timestamp.valueOf(now), id);
    }
}
