package com.example.starter.ros;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 集成测试基类：使用独立内存库 ros_registry_test，每个用例前清空全部数据。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractRegistryIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM node_resource");
        jdbcTemplate.update("DELETE FROM resource_event");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM ros_node");
        jdbcTemplate.update("DELETE FROM registry_checkpoint");
        jdbcTemplate.update("UPDATE revision_seq SET next_value = 0 WHERE id = 1");
    }
}
