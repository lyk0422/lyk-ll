package com.example.starter.atm.repo;

import com.example.starter.atm.domain.EscortCheckin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 押运打卡数据访问。(plan_id, guard_id) 唯一约束保证同人重复打卡被数据库拒绝。
 */
@Repository
public class CheckinRepository {

    private static final RowMapper<EscortCheckin> MAPPER = (rs, n) -> new EscortCheckin(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getLong("guard_id"),
            rs.getInt("seq"),
            rs.getLong("checkin_at"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public CheckinRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long planId, long guardId, int seq, long checkinAt, long createdAt) {
        jdbc.update(
                "INSERT INTO escort_checkin (plan_id, guard_id, seq, checkin_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                planId, guardId, seq, checkinAt, createdAt);
    }

    /** 按打卡时刻先后返回，seq 由此定序。 */
    public List<EscortCheckin> findByPlanId(long planId) {
        return jdbc.query(
                "SELECT * FROM escort_checkin WHERE plan_id = ? ORDER BY checkin_at, id",
                MAPPER, planId);
    }

    public boolean exists(long planId, long guardId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM escort_checkin WHERE plan_id = ? AND guard_id = ?",
                Integer.class, planId, guardId);
        return c != null && c > 0;
    }
}
