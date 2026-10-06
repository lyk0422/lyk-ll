package com.example.starter.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 展示登记数据访问。
 */
@Repository
public class ExhibitionRepository {

    private final JdbcClient jdbc;

    public ExhibitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入展示登记并返回主键。
     */
    public long insert(long zoneId, String groupName, LocalDateTime registeredAt, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO exhibition_registration (zone_id, group_name, registered_at, request_id)"
                        + " VALUES (:zoneId, :groupName, :registeredAt, :requestId)")
                .param("zoneId", zoneId)
                .param("groupName", groupName)
                .param("registeredAt", registeredAt)
                .param("requestId", requestId)
                .update(keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * 统计某池登记数，供测试断言。
     */
    public int countByZone(long zoneId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM exhibition_registration WHERE zone_id = :zoneId")
                .param("zoneId", zoneId)
                .query(Long.class)
                .single();
        return count.intValue();
    }
}
