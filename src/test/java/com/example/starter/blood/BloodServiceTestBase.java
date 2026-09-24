package com.example.starter.blood;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 业务测试基类：启动完整 Spring 上下文（H2 命名内存库），
 * 每个用例前清空数据并重置可控时钟，避免用例间顺序依赖。
 */
@SpringBootTest
@Import(TestInfraConfig.class)
public abstract class BloodServiceTestBase {

    /** 用例默认当前时刻（UTC）。 */
    protected static final Instant DEFAULT_NOW = Instant.parse("2026-09-24T00:00:00Z");

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.sql("DELETE FROM late_event").update();
        jdbc.sql("DELETE FROM issue_record").update();
        jdbc.sql("DELETE FROM release_request").update();
        jdbc.sql("DELETE FROM blood_unit").update();
        clock.setInstant(DEFAULT_NOW);
    }
}
