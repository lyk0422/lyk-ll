package com.example.starter.repo;

import com.example.starter.domain.Alarm;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 水质告警数据访问。
 */
@Repository
public class AlarmRepository {

    private final JdbcClient jdbc;

    public AlarmRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入告警并返回主键，初始状态 OPEN。
     */
    public long insert(long zoneId, String params, String detail, long sampleId, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO alarm (zone_id, params, detail, status, sample_id, created_at)"
                        + " VALUES (:zoneId, :params, :detail, 'OPEN', :sampleId, :createdAt)")
                .param("zoneId", zoneId)
                .param("params", params)
                .param("detail", detail)
                .param("sampleId", sampleId)
                .param("createdAt", createdAt)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 查询未解除（OPEN/ESCALATED）的告警，按时间升序。
     */
    public List<Alarm> findUnresolved(long zoneId) {
        return jdbc.sql("SELECT * FROM alarm WHERE zone_id = :zoneId AND status <> 'RESOLVED'"
                        + " ORDER BY id")
                .param("zoneId", zoneId)
                .query(Alarm.class)
                .list();
    }

    public List<Alarm> findByZone(long zoneId) {
        return jdbc.sql("SELECT * FROM alarm WHERE zone_id = :zoneId ORDER BY id")
                .param("zoneId", zoneId)
                .query(Alarm.class)
                .list();
    }

    /**
     * 升级为紧急告警。
     */
    public void escalate(long id) {
        jdbc.sql("UPDATE alarm SET status = 'ESCALATED' WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * 解除告警。
     */
    public void resolve(long id, LocalDateTime resolvedAt) {
        jdbc.sql("UPDATE alarm SET status = 'RESOLVED', resolved_at = :resolvedAt WHERE id = :id")
                .param("id", id)
                .param("resolvedAt", resolvedAt)
                .update();
    }
}
