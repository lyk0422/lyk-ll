package com.example.starter.repository;

import com.example.starter.domain.AdjustmentGroup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 联调组数据访问。
 */
@Repository
public class AdjustmentGroupRepository {

    private static final RowMapper<AdjustmentGroup> MAPPER = (rs, n) -> new AdjustmentGroup(
            rs.getLong("id"), rs.getLong("station_id"),
            AdjustmentGroup.GroupStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("dissolved_at")).map(Timestamp::toInstant).orElse(null),
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getString("dissolve_request_id"), rs.getString("dissolve_fingerprint"));

    private static final String COLUMNS = "id, station_id, status, version, created_at, dissolved_at,"
            + " request_id, fingerprint, dissolve_request_id, dissolve_fingerprint";

    private final JdbcTemplate jdbc;

    public AdjustmentGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入联调组并返回生成的主键。
     */
    public long insert(AdjustmentGroup group) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO adjustment_group"
                            + " (station_id, status, version, created_at, dissolved_at,"
                            + " request_id, fingerprint)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, group.stationId());
            ps.setString(2, group.status().name());
            ps.setInt(3, group.version());
            ps.setTimestamp(4, Timestamp.from(group.createdAt()));
            ps.setTimestamp(5, group.dissolvedAt() == null ? null : Timestamp.from(group.dissolvedAt()));
            ps.setString(6, group.requestId());
            ps.setString(7, group.fingerprint());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按 ID 查询联调组。
     */
    public Optional<AdjustmentGroup> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM adjustment_group WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行级写锁（SELECT ... FOR UPDATE），
     * 用于联调提交/解除与建组的串行化（锁顺序：station → group → valve → unseal）。
     */
    public Optional<AdjustmentGroup> lockById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM adjustment_group WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按建组幂等键查询联调组。
     */
    public Optional<AdjustmentGroup> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM adjustment_group WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    /**
     * 联调成功后原子推进联调单版本：仅当版本一致且仍 ACTIVE 时生效，返回更新行数。
     */
    public int bumpVersion(long id, int expectedVersion) {
        return jdbc.update("UPDATE adjustment_group SET version = version + 1"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                id, expectedVersion);
    }

    /**
     * 原子解除：仅当版本一致且仍 ACTIVE 时生效，返回更新行数；仅释放组归属。
     */
    public int dissolve(long id, int expectedVersion, Instant dissolvedAt,
                        String dissolveRequestId, String dissolveFingerprint) {
        return jdbc.update("UPDATE adjustment_group SET status = 'DISSOLVED', dissolved_at = ?,"
                        + " version = version + 1, dissolve_request_id = ?, dissolve_fingerprint = ?"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                Timestamp.from(dissolvedAt), dissolveRequestId, dissolveFingerprint,
                id, expectedVersion);
    }
}
