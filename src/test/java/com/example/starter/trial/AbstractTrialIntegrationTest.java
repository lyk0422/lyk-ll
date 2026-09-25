package com.example.starter.trial;

import com.example.starter.trial.domain.DataSeeder;
import com.example.starter.trial.domain.TimeProvider;
import com.example.starter.trial.domain.TrialConstants;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

/**
 * H2（MODE=MySQL）集成测试基类：真实 Spring 上下文、真实事务与唯一约束。
 * 每个用例前清空业务数据并将中心区组状态、随机号台账重置为初始值，消除顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractTrialIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected DataSeeder dataSeeder;

    @Autowired
    protected TimeProvider timeProvider;

    @BeforeEach
    void resetDatabase() {
        timeProvider.resetClock();
        jdbcTemplate.update("DELETE FROM unblind_audit");
        jdbcTemplate.update("DELETE FROM unblind_event");
        jdbcTemplate.update("DELETE FROM subject_visit");
        jdbcTemplate.update("DELETE FROM subject_enrollment");
        jdbcTemplate.update("DELETE FROM center_allocation_state");
        jdbcTemplate.update("DELETE FROM random_number_ledger");
        jdbcTemplate.update("DELETE FROM trial_site");
        jdbcTemplate.update("DELETE FROM authorized_unblinder");
        dataSeeder.seedAll();
        List<String> centers = List.of(DataSeeder.CENTER_SHANGHAI, DataSeeder.CENTER_BEIJING);
        Integer siteCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM trial_site WHERE trial_code = ?",
                Integer.class, TrialConstants.DEFAULT_TRIAL);
        org.junit.jupiter.api.Assertions.assertEquals(2, siteCount);
    }
}
