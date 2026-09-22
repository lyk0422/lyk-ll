package com.example.starter.arbitration.repo;

import com.example.starter.arbitration.domain.SafetyEventPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * 安全降级事件表 t_safety_event 数据访问。event_key 唯一约束保证降级动作只执行一次。
 */
@Repository
public class SafetyEventRepository {

    private static final RowMapper<SafetyEventPo> MAPPER = (rs, n) -> {
        SafetyEventPo po = new SafetyEventPo();
        po.setId(rs.getLong("id"));
        po.setEventKey(rs.getString("event_key"));
        po.setReason(rs.getString("reason"));
        po.setResourceName(rs.getString("resource_name"));
        po.setControllerId(rs.getString("controller_id"));
        po.setAction(rs.getString("action"));
        po.setTargetPosition((Double) rs.getObject("target_position"));
        po.setEventMs(rs.getLong("event_ms"));
        return po;
    };

    private final JdbcTemplate jdbc;

    public SafetyEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新增事件，返回自增主键；event_key 重复时由唯一约束抛 DuplicateKeyException。 */
    public long insert(SafetyEventPo event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_safety_event "
                            + "(event_key, reason, resource_name, controller_id, action, target_position, event_ms) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, event.getEventKey());
            ps.setString(2, event.getReason());
            ps.setString(3, event.getResourceName());
            ps.setString(4, event.getControllerId());
            ps.setString(5, event.getAction());
            if (event.getTargetPosition() == null) {
                ps.setObject(6, null);
            } else {
                ps.setDouble(6, event.getTargetPosition());
            }
            ps.setLong(7, event.getEventMs());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按事件键查询。 */
    public Optional<SafetyEventPo> findByKey(String eventKey) {
        return jdbc.query("SELECT * FROM t_safety_event WHERE event_key = ?", MAPPER, eventKey)
                .stream().findFirst();
    }

    /** 全部事件（按时间、id 升序）。 */
    public List<SafetyEventPo> findAll() {
        return jdbc.query("SELECT * FROM t_safety_event ORDER BY id", MAPPER);
    }
}
