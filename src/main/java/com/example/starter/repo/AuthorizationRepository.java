package com.example.starter.repo;

import com.example.starter.domain.AuthorizationStatus;
import com.example.starter.domain.ZoneAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 重叠区授权数据访问。request_id 唯一约束支撑幂等；冲突检测在调用方持有区域行锁后执行。
 */
@Repository
public class AuthorizationRepository {

    private static final RowMapper<ZoneAuthorization> MAPPER = (rs, n) -> new ZoneAuthorization(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("crane_id"),
            rs.getString("zone_id"),
            rs.getTimestamp("start_time_utc").toInstant(),
            rs.getTimestamp("end_time_utc").toInstant(),
            rs.getBigDecimal("load_tonnes"),
            AuthorizationStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("released_at_utc") == null ? null : rs.getTimestamp("released_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public AuthorizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ZoneAuthorization insert(ZoneAuthorization auth) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO zone_authorization (request_id, fingerprint, crane_id, zone_id,"
                            + " start_time_utc, end_time_utc, load_tonnes, status, created_at_utc, released_at_utc)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, auth.requestId());
            ps.setString(2, auth.fingerprint());
            ps.setString(3, auth.craneId());
            ps.setString(4, auth.zoneId());
            ps.setTimestamp(5, Timestamp.from(auth.startTimeUtc()));
            ps.setTimestamp(6, Timestamp.from(auth.endTimeUtc()));
            ps.setBigDecimal(7, auth.loadTonnes());
            ps.setString(8, auth.status().name());
            ps.setTimestamp(9, Timestamp.from(auth.createdAtUtc()));
            if (auth.releasedAtUtc() == null) {
                ps.setTimestamp(10, null);
            } else {
                ps.setTimestamp(10, Timestamp.from(auth.releasedAtUtc()));
            }
            return ps;
        }, keys);
        Long id = keys.getKey() == null ? null : keys.getKey().longValue();
        return new ZoneAuthorization(id, auth.requestId(), auth.fingerprint(), auth.craneId(), auth.zoneId(),
                auth.startTimeUtc(), auth.endTimeUtc(), auth.loadTonnes(), auth.status(),
                auth.createdAtUtc(), auth.releasedAtUtc());
    }

    public Optional<ZoneAuthorization> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM zone_authorization WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<ZoneAuthorization> findById(long id) {
        return jdbc.query("SELECT * FROM zone_authorization WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 在当前事务内按主键加写锁读取授权，用于释放操作串行化。
     */
    public Optional<ZoneAuthorization> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM zone_authorization WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询同一重叠区内与 [start, end) 半开区间重叠的生效中授权（端点相接不算冲突）。
     */
    public List<ZoneAuthorization> findActiveOverlapping(String zoneId, Instant start, Instant end) {
        return jdbc.query("SELECT * FROM zone_authorization WHERE zone_id = ? AND status = 'ACTIVE'"
                        + " AND start_time_utc < ? AND end_time_utc > ? ORDER BY id",
                MAPPER, zoneId, Timestamp.from(end), Timestamp.from(start));
    }

    /**
     * 查询塔机在指定重叠区内、时间窗覆盖 ts 的授权（含已释放，由领域规则按释放时刻判定）。
     */
    public List<ZoneAuthorization> findCovering(String craneId, String zoneId, Instant ts) {
        return jdbc.query("SELECT * FROM zone_authorization WHERE crane_id = ? AND zone_id = ?"
                        + " AND start_time_utc <= ? AND end_time_utc >= ? ORDER BY id",
                MAPPER, craneId, zoneId, Timestamp.from(ts), Timestamp.from(ts));
    }

    public void markReleased(long id, AuthorizationStatus status, Instant releasedAtUtc) {
        jdbc.update("UPDATE zone_authorization SET status = ?, released_at_utc = ? WHERE id = ?",
                status.name(), Timestamp.from(releasedAtUtc), id);
    }
}
