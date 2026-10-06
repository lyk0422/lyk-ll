package com.example.starter.repo;

import com.example.starter.domain.SensorState;
import com.example.starter.domain.ZonePool;
import com.example.starter.domain.ZoneStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 展示池分区数据访问。
 */
@Repository
public class ZoneRepository {

    private final JdbcClient jdbc;

    public ZoneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ZonePool> findByCode(String code) {
        return jdbc.sql("SELECT * FROM zone_pool WHERE code = :code")
                .param("code", code)
                .query(ZonePool.class)
                .optional();
    }

    public Optional<ZonePool> findById(long id) {
        return jdbc.sql("SELECT * FROM zone_pool WHERE id = :id")
                .param("id", id)
                .query(ZonePool.class)
                .optional();
    }

    public List<ZonePool> listAll() {
        return jdbc.sql("SELECT * FROM zone_pool ORDER BY id")
                .query(ZonePool.class)
                .list();
    }

    /**
     * 更新池运行状态字段（不含编码与名称）。
     */
    public void updateState(ZonePool zone) {
        jdbc.sql("UPDATE zone_pool SET status = :status, consecutive_violations = :consecutiveViolations,"
                        + " sensor_state = :sensorState, last_heartbeat_at = :lastHeartbeatAt,"
                        + " missing_since = :missingSince, updated_at = :updatedAt WHERE id = :id")
                .param("status", zone.status().name())
                .param("consecutiveViolations", zone.consecutiveViolations())
                .param("sensorState", zone.sensorState().name())
                .param("lastHeartbeatAt", zone.lastHeartbeatAt())
                .param("missingSince", zone.missingSince())
                .param("updatedAt", zone.updatedAt())
                .param("id", zone.id())
                .update();
    }

    /**
     * 仅更新传感器心跳与状态字段。
     */
    public void updateSensor(long id, SensorState sensorState, java.time.LocalDateTime lastHeartbeatAt,
                             java.time.LocalDateTime missingSince, java.time.LocalDateTime updatedAt) {
        jdbc.sql("UPDATE zone_pool SET sensor_state = :sensorState, last_heartbeat_at = :lastHeartbeatAt,"
                        + " missing_since = :missingSince, updated_at = :updatedAt WHERE id = :id")
                .param("sensorState", sensorState.name())
                .param("lastHeartbeatAt", lastHeartbeatAt)
                .param("missingSince", missingSince)
                .param("updatedAt", updatedAt)
                .param("id", id)
                .update();
    }

    /**
     * 供测试直接修正池状态与连续越限计数。
     */
    public void updateStatus(long id, ZoneStatus status, int consecutiveViolations,
                             java.time.LocalDateTime updatedAt) {
        jdbc.sql("UPDATE zone_pool SET status = :status, consecutive_violations = :consecutiveViolations,"
                        + " updated_at = :updatedAt WHERE id = :id")
                .param("status", status.name())
                .param("consecutiveViolations", consecutiveViolations)
                .param("updatedAt", updatedAt)
                .param("id", id)
                .update();
    }
}
