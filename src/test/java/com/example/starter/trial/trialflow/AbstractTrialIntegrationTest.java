package com.example.starter.trial.trialflow;

import com.example.starter.trial.support.MutableClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 集成测试基类：每例前清空业务表（保留合成授权用户），时钟复位。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MutableClockConfig.class)
abstract class AbstractTrialIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClockConfig.MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM enrollment_request");
        jdbc.update("DELETE FROM unblinding_audit_event");
        jdbc.update("DELETE FROM unblinding_request");
        jdbc.update("DELETE FROM visit_record");
        jdbc.update("DELETE FROM trial_subject");
        jdbc.update("DELETE FROM site_random_sequence");
        jdbc.update("DELETE FROM site_enrollment_lock");
        jdbc.update("DELETE FROM site_random_seed");
        jdbc.update("ALTER TABLE site_random_sequence ALTER COLUMN id RESTART WITH 1");
        clock.set(java.time.Instant.parse("2026-03-10T08:00:00Z"));
    }
}
