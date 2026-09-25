package com.example.starter;

import com.example.starter.command.CommandService;
import com.example.starter.emission.EmissionService;
import com.example.starter.license.LicenseService;
import com.example.starter.plan.PlanService;
import com.example.starter.settlement.SettlementService;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfiguration;
import com.example.starter.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2（MODE=MySQL）集成测试基类：每例清表并重置可控时钟，场景间无顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected LicenseService licenseService;
    @Autowired
    protected PlanService planService;
    @Autowired
    protected EmissionService emissionService;
    @Autowired
    protected CommandService commandService;
    @Autowired
    protected SettlementService settlementService;

    @BeforeEach
    void resetDatabaseAndClock() {
        TestFixtures.cleanAll(jdbc);
        clock.setTime(TestClockConfiguration.INITIAL_TIME);
    }
}
