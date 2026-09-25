package com.example.starter.weight;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.weight.support.MutableClock;
import com.example.starter.weight.support.TestClockConfig;

/**
 * 集成测试基类：独立 H2 内存库（weight_test），每个用例前清空业务数据并重置时钟，
 * 避免用例间顺序依赖；合成基础数据（车辆/限值/检测站）由 data.sql 提供。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    protected static final Instant WEIGH_TIME = Instant.parse("2026-09-01T02:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock mutableClock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM late_weigh_event");
        jdbc.update("DELETE FROM review_request");
        jdbc.update("DELETE FROM points_ledger");
        jdbc.update("DELETE FROM fine_ledger");
        jdbc.update("DELETE FROM decision_doc");
        jdbc.update("DELETE FROM weigh_record");
        jdbc.update("DELETE FROM enforcement_case");
        jdbc.update("ALTER SEQUENCE seq_case_no RESTART WITH 1");
        jdbc.update("ALTER SEQUENCE seq_doc_no RESTART WITH 1");
        mutableClock.setInstant(TestClockConfig.START);
    }
}
