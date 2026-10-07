package com.example.starter.repository;

import com.example.starter.domain.CoupledGroup;
import com.example.starter.domain.CoupledGroupMember;
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
 * 联调组及成员数据访问。
 */
@Repository
public class CoupledGroupRepository {

    private static final RowMapper<CoupledGroup> GROUP_MAPPER = (rs, n) -> new CoupledGroup(
            rs.getLong("id"), rs.getLong("station_id"),
            CoupledGroup.GroupStatus.valueOf(rs.getString("status")),
            rs.getInt("version"), rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("dissolved_at")).map(Timestamp::toInstant).orElse(null));

    private static final RowMapper<CoupledGroupMember> MEMBER_MAPPER = (rs, n) -> new CoupledGroupMember(
            rs.getLong("id"), rs.getLong("group_id"), rs.getLong("valve_id"),
            rs.getInt("weight"), rs.getInt("valve_version"));

    private static final String GROUP_COLUMNS = "id, station_id, status, version,"
            + " request_id, fingerprint, created_at, dissolved_at";

    private final JdbcTemplate jdbc;

    public CoupledGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入联调组并返回生成的主键。
     */
    public long insert(CoupledGroup group) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO coupled_group"
                            + " (station_id, status, version, request_id, fingerprint,"
                            + " created_at, dissolved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, group.stationId());
            ps.setString(2, group.status().name());
            ps.setInt(3, group.version());
            ps.setString(4, group.requestId());
            ps.setString(5, group.fingerprint());
            ps.setTimestamp(6, Timestamp.from(group.createdAt()));
            ps.setTimestamp(7, group.dissolvedAt() == null ? null : Timestamp.from(group.dissolvedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按 ID 查询联调组。
     */
    public Optional<CoupledGroup> findById(long id) {
        return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM coupled_group WHERE id = ?",
                GROUP_MAPPER, id).stream().findFirst();
    }

    /**
     * 按建组幂等键查询联调组。
     */
    public Optional<CoupledGroup> findByRequestId(String requestId) {
        return jdbc.query("SELECT " + GROUP_COLUMNS + " FROM coupled_group WHERE request_id = ?",
                GROUP_MAPPER, requestId).stream().findFirst();
    }

    /**
     * 原子修改：仅当版本一致且仍 ACTIVE 时推进版本，返回更新行数。
     */
    public int bumpVersion(long id, int expectedVersion) {
        return jdbc.update("UPDATE coupled_group SET version = version + 1"
                + " WHERE id = ? AND version = ? AND status = 'ACTIVE'", id, expectedVersion);
    }

    /**
     * 原子解除：仅当版本一致且仍 ACTIVE 时置为 DISSOLVED，返回更新行数。
     */
    public int dissolve(long id, int expectedVersion, Instant dissolvedAt) {
        return jdbc.update("UPDATE coupled_group SET status = 'DISSOLVED', dissolved_at = ?,"
                        + " version = version + 1 WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                Timestamp.from(dissolvedAt), id, expectedVersion);
    }

    /**
     * 追加组成员（建组/改组时冻结权重与开度版本）。
     */
    public void insertMember(long groupId, long valveId, int weight, int valveVersion) {
        jdbc.update("INSERT INTO coupled_group_member (group_id, valve_id, weight, valve_version)"
                + " VALUES (?, ?, ?, ?)", groupId, valveId, weight, valveVersion);
    }

    /**
     * 删除组全部成员（改组时整体替换，历史联调明细不受影响）。
     */
    public void deleteMembers(long groupId) {
        jdbc.update("DELETE FROM coupled_group_member WHERE group_id = ?", groupId);
    }

    /**
     * 查询组成员，按阀门 ID 升序（确定性的锁顺序与指纹顺序）。
     */
    public List<CoupledGroupMember> findMembers(long groupId) {
        return jdbc.query("SELECT id, group_id, valve_id, weight, valve_version"
                + " FROM coupled_group_member WHERE group_id = ? ORDER BY valve_id", MEMBER_MAPPER, groupId);
    }

    /**
     * 查询阀门当前所属的活动组，无则空（阀只能属于一个活动组）。
     */
    public Optional<CoupledGroup> findActiveGroupByValveId(long valveId) {
        return jdbc.query("SELECT g." + GROUP_COLUMNS.replace(", ", ", g.")
                        + " FROM coupled_group g JOIN coupled_group_member m ON m.group_id = g.id"
                        + " WHERE m.valve_id = ? AND g.status = 'ACTIVE'",
                GROUP_MAPPER, valveId).stream().findFirst();
    }
}
