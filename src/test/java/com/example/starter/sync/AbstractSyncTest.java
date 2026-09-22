package com.example.starter.sync;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.sync.engine.SyncEngine;

/**
 * 同步服务测试基类：每个用例前清空 H2 数据与内存引擎状态，保证用例间隔离。
 */
@SpringBootTest
public abstract class AbstractSyncTest {

    protected static final long TARGET = 1_000_000L;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SyncEngine engine;

    @BeforeEach
    void resetState() {
        engine.reset();
        jdbc.update("DELETE FROM fusion_frame_input");
        jdbc.update("DELETE FROM fusion_frame");
        jdbc.update("DELETE FROM sensor_message");
        jdbc.update("DELETE FROM drift_correction");
        jdbc.update("DELETE FROM sync_event");
    }

    /**
     * 统计某传感器某消息ID在消息表中的行数。
     */
    protected int messageCount(SensorType type, String messageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sensor_message WHERE sensor_type = ? AND message_id = ?",
                Integer.class, type.name(), messageId);
        return count == null ? 0 : count;
    }

    /**
     * 统计已提交帧数量。
     */
    protected int frameCount(long targetTs) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fusion_frame WHERE target_ts = ?",
                Integer.class, targetTs);
        return count == null ? 0 : count;
    }
}
