package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类：共享应用上下文，每个用例前清空业务表（卫星/地面站/窗口种子数据保留），
 * 保证用例间无顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessTables() {
        jdbc.update("DELETE FROM late_report_event");
        jdbc.update("DELETE FROM window_outcome");
        jdbc.update("DELETE FROM replan_suggestion");
        jdbc.update("DELETE FROM schedule_entry");
        jdbc.update("DELETE FROM schedule_request");
        jdbc.update("DELETE FROM publication");
    }
}
