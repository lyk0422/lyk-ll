package com.example.starter.arbitration.repo;

import com.example.starter.arbitration.domain.ControllerPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 控制器表 t_controller 数据访问。
 */
@Repository
public class ControllerRepository {

    private static final RowMapper<ControllerPo> MAPPER = (rs, n) -> {
        ControllerPo po = new ControllerPo();
        po.setControllerId(rs.getString("controller_id"));
        po.setLastHeartbeatMs(rs.getLong("last_heartbeat_ms"));
        po.setLost(rs.getBoolean("lost"));
        po.setVersion(rs.getLong("version"));
        return po;
    };

    private final JdbcTemplate jdbc;

    public ControllerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 心跳：不存在则插入；存在则刷新时间，若已失联则清除失联标记并恢复版本。 */
    public void heartbeat(String controllerId, long nowMs) {
        int updated = jdbc.update(
                "UPDATE t_controller SET last_heartbeat_ms = ?, lost = FALSE, version = version + 1 "
                        + "WHERE controller_id = ?",
                nowMs, controllerId);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO t_controller (controller_id, last_heartbeat_ms, lost, version) "
                                + "VALUES (?, ?, FALSE, 0)",
                        controllerId, nowMs);
            } catch (org.springframework.dao.DuplicateKeyException concurrentInsert) {
                // 并发首次心跳：对方已插入，补一次更新
                jdbc.update(
                        "UPDATE t_controller SET last_heartbeat_ms = ?, lost = FALSE, version = version + 1 "
                                + "WHERE controller_id = ?",
                        nowMs, controllerId);
            }
        }
    }

    /** 查询控制器。 */
    public Optional<ControllerPo> find(String controllerId) {
        return jdbc.query("SELECT * FROM t_controller WHERE controller_id = ?", MAPPER, controllerId)
                .stream().findFirst();
    }

    /** 事务内锁定查询。 */
    public Optional<ControllerPo> findForUpdate(String controllerId) {
        return jdbc.query("SELECT * FROM t_controller WHERE controller_id = ? FOR UPDATE", MAPPER, controllerId)
                .stream().findFirst();
    }

    /** 心跳超时且尚未标记失联的控制器（事务内锁定）。 */
    public List<ControllerPo> findTimedOutForUpdate(long nowMs, long timeoutMs) {
        return jdbc.query(
                "SELECT * FROM t_controller WHERE last_heartbeat_ms < ? AND lost = FALSE FOR UPDATE",
                MAPPER, nowMs - timeoutMs);
    }

    /** 条件标记失联（仅当未标记时），返回受影响行数。 */
    public int markLost(String controllerId) {
        return jdbc.update(
                "UPDATE t_controller SET lost = TRUE, version = version + 1 "
                        + "WHERE controller_id = ? AND lost = FALSE",
                controllerId);
    }
}
