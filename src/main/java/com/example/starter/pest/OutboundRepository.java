package com.example.starter.pest;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 出库登记数据访问。
 */
@Repository
public class OutboundRepository {

    private final JdbcClient jdbc;

    public OutboundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新增出库登记，返回自增 ID。
     */
    public long insert(String requestId, String zoneCode, String item, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO outbound_registration (request_id, zone_code, item, created_at)"
                        + " VALUES (:requestId, :zoneCode, :item, :createdAt)")
                .param("requestId", requestId)
                .param("zoneCode", zoneCode)
                .param("item", item)
                .param("createdAt", Timestamp.from(createdAt))
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }
}
