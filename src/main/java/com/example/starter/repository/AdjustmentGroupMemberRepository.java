package com.example.starter.repository;

import com.example.starter.domain.AdjustmentGroupMember;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 联调组成员数据访问。
 */
@Repository
public class AdjustmentGroupMemberRepository {

    private static final RowMapper<AdjustmentGroupMember> MAPPER = (rs, n) ->
            new AdjustmentGroupMember(rs.getLong("id"), rs.getLong("group_id"),
                    rs.getLong("valve_id"), rs.getInt("weight"),
                    rs.getInt("frozen_valve_version"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public AdjustmentGroupMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入成员；必须与联调组创建在同一事务内调用。
     */
    public void insert(long groupId, long valveId, int weight, int frozenValveVersion,
                       Instant createdAt) {
        jdbc.update("INSERT INTO adjustment_group_member"
                        + " (group_id, valve_id, weight, frozen_valve_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                groupId, valveId, weight, frozenValveVersion, Timestamp.from(createdAt));
    }

    /**
     * 查询联调组全部成员，按阀门 ID 升序。
     */
    public List<AdjustmentGroupMember> findByGroupId(long groupId) {
        return jdbc.query("SELECT id, group_id, valve_id, weight, frozen_valve_version, created_at"
                + " FROM adjustment_group_member WHERE group_id = ? ORDER BY valve_id", MAPPER, groupId);
    }

    /**
     * 阀门是否已属于某个活动（ACTIVE）联调组。
     */
    public boolean existsActiveByValveId(long valveId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM adjustment_group_member m"
                        + " JOIN adjustment_group g ON g.id = m.group_id"
                        + " WHERE m.valve_id = ? AND g.status = 'ACTIVE'",
                Integer.class, valveId);
        return count != null && count > 0;
    }
}
