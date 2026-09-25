package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.config.ApplicationClock;

/**
 * 测试基类：真实 Spring 上下文 + H2(MODE=MySQL)，每例清理业务表（保留合成许可），
 * 并将可控时钟恢复到固定基准时刻，保证场景间无顺序依赖。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    protected static final String KILN = "KILN-A";
    protected static final String VALID_LICENSE = "HW-VALID-0001";
    protected static final String EXPIRED_LICENSE = "HW-EXPIRED-0002";

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ApplicationClock clock;

    @BeforeEach
    void cleanBase() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : new String[] {
                "daily_summary", "ban_record", "late_reading_event", "idempotent_request",
                "reduction_command", "hourly_assessment", "emission_reading",
                "kiln_active_plan", "kiln_lock", "plan_fuel", "co_firing_plan"}) {
            jdbc.update("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        clock.reset();
    }
}
