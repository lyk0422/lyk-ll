package com.example.starter.arbitration.repo;

import com.example.starter.arbitration.domain.ResourcePo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 资源表 t_resource 数据访问。
 */
@Repository
public class ResourceRepository {

    private static final RowMapper<ResourcePo> MAPPER = (rs, n) -> {
        ResourcePo po = new ResourcePo();
        po.setResourceName(rs.getString("resource_name"));
        po.setMinPosition(rs.getDouble("min_position"));
        po.setMaxPosition(rs.getDouble("max_position"));
        po.setMaxVelocity(rs.getDouble("max_velocity"));
        po.setMaxAcceleration(rs.getDouble("max_acceleration"));
        po.setSafetyAction(rs.getString("safety_action"));
        po.setPresetPosition((Double) rs.getObject("preset_position"));
        po.setLastPosition((Double) rs.getObject("last_position"));
        po.setLastTimestampMs((Long) rs.getObject("last_timestamp_ms"));
        return po;
    };

    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记或更新资源定义（不影响 last_position/last_timestamp_ms 运行态字段）。 */
    public void upsert(ResourcePo resource) {
        // MERGE 仅列出配置列：新行的运行态列取默认值 NULL，已存在行只更新配置列
        jdbc.update(
                "MERGE INTO t_resource "
                        + "(resource_name, min_position, max_position, max_velocity, max_acceleration, "
                        + "safety_action, preset_position) "
                        + "KEY (resource_name) VALUES (?, ?, ?, ?, ?, ?, ?)",
                resource.getResourceName(), resource.getMinPosition(), resource.getMaxPosition(),
                resource.getMaxVelocity(), resource.getMaxAcceleration(), resource.getSafetyAction(),
                resource.getPresetPosition());
    }

    /** 按名查询资源。 */
    public Optional<ResourcePo> findByName(String resourceName) {
        return jdbc.query("SELECT * FROM t_resource WHERE resource_name = ?", MAPPER, resourceName)
                .stream().findFirst();
    }

    /** 锁定资源行（SELECT ... FOR UPDATE），必须在事务内调用。 */
    public Optional<ResourcePo> findByNameForUpdate(String resourceName) {
        return jdbc.query("SELECT * FROM t_resource WHERE resource_name = ? FOR UPDATE", MAPPER, resourceName)
                .stream().findFirst();
    }

    /** 更新最近指令目标与时戳（指令执行后）。 */
    public void updateLastTarget(String resourceName, Double position, Long timestampMs) {
        jdbc.update("UPDATE t_resource SET last_position = ?, last_timestamp_ms = ? WHERE resource_name = ?",
                position, timestampMs, resourceName);
    }

    /** 全部资源。 */
    public List<ResourcePo> findAll() {
        return jdbc.query("SELECT * FROM t_resource ORDER BY resource_name", MAPPER);
    }
}
