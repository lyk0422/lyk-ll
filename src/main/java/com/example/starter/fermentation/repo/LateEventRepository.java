package com.example.starter.fermentation.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 迟到事件仓储：批次清酒转出后到达的温度/双乙酰数据不进入业务链，仅记录迟到事件。
 */
@Repository
public class LateEventRepository {

    private final JdbcClient jdbc;

    public LateEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条终态后迟到事件，payloadJson 为请求内容快照。
     */
    public void insert(long batchId, String eventType, String payloadJson) {
        jdbc.sql("INSERT INTO late_event (batch_id, event_type, payload_json) VALUES (?, ?, ?)")
                .param(batchId)
                .param(eventType)
                .param(payloadJson)
                .update();
    }

    /**
     * 统计批次迟到事件数量，测试断言用。
     */
    public int count(long batchId, String eventType) {
        return jdbc.sql("SELECT COUNT(*) FROM late_event WHERE batch_id = ? AND event_type = ?")
                .param(batchId)
                .param(eventType)
                .query(Integer.class)
                .single();
    }
}
