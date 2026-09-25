package com.example.starter.wbb;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2（MODE=MySQL）集成测试基类：场景间清空业务表，机型/包线合成数据仅初始化一次并保留。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractH2IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessTables() {
        jdbc.update("DELETE FROM manifest_review");
        jdbc.update("DELETE FROM payload_item");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM manifest");
        jdbc.update("DELETE FROM flight_active_manifest");
        jdbc.update("DELETE FROM idempotent_request");
    }
}
