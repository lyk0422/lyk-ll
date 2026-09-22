package com.example.starter.arbitration.repo;

import com.example.starter.arbitration.domain.EstopStatePo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 急停状态表 t_estop_state（单行 id=1）数据访问。
 */
@Repository
public class EstopStateRepository {

    private static final RowMapper<EstopStatePo> MAPPER = (rs, n) -> {
        EstopStatePo po = new EstopStatePo();
        po.setId(rs.getLong("id"));
        po.setActive(rs.getBoolean("active"));
        po.setTriggeredAtMs((Long) rs.getObject("triggered_at_ms"));
        po.setRecoveredAtMs((Long) rs.getObject("recovered_at_ms"));
        return po;
    };

    private final JdbcTemplate jdbc;

    public EstopStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 事务内锁定并返回急停状态行。 */
    public EstopStatePo getForUpdate() {
        return jdbc.queryForObject("SELECT * FROM t_estop_state WHERE id = 1 FOR UPDATE", MAPPER);
    }

    /** 返回急停状态行（不加锁）。 */
    public EstopStatePo get() {
        return jdbc.queryForObject("SELECT * FROM t_estop_state WHERE id = 1", MAPPER);
    }

    /** 触发急停（仅当当前非急停时），返回是否发生了状态翻转。 */
    public boolean triggerIfInactive(long nowMs) {
        return jdbc.update(
                "UPDATE t_estop_state SET active = TRUE, triggered_at_ms = ? WHERE id = 1 AND active = FALSE",
                nowMs) > 0;
    }

    /** 显式恢复（仅当当前急停时），返回是否发生了状态翻转。 */
    public boolean recoverIfActive(long nowMs) {
        return jdbc.update(
                "UPDATE t_estop_state SET active = FALSE, recovered_at_ms = ? WHERE id = 1 AND active = TRUE",
                nowMs) > 0;
    }
}
