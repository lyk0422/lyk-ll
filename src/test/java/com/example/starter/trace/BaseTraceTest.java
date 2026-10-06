package com.example.starter.trace;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 追溯服务测试基类：独立 H2 内存库，每个用例前清空业务数据（保留蜂场主数据）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(H2ShutdownConfig.class)
public abstract class BaseTraceTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM processing_batch");
        jdbc.update("DELETE FROM apiary_alert");
        jdbc.update("DELETE FROM retest");
        jdbc.update("DELETE FROM rapid_test");
        jdbc.update("DELETE FROM test_chain");
        jdbc.update("DELETE FROM harvest_contribution");
        jdbc.update("DELETE FROM harvest_batch");
    }
}
