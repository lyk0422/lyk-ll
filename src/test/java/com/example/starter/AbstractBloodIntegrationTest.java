package com.example.starter;

import com.example.starter.repository.BloodIssueRepository;
import com.example.starter.repository.BloodUnitRepository;
import com.example.starter.repository.ReleaseOrderRepository;
import com.example.starter.repository.TestResultEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * H2（MySQL 兼容模式）集成测试基类：独立内存库、可控 UTC 时钟、用例间清理数据。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:blood_release_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always"
        })
@Import(ClockTestConfig.class)
public abstract class AbstractBloodIntegrationTest {

    protected static final Instant BASE_TIME = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock mutableClock;

    @Autowired
    protected BloodUnitRepository unitRepository;

    @Autowired
    protected TestResultEventRepository eventRepository;

    @Autowired
    protected ReleaseOrderRepository orderRepository;

    @Autowired
    protected BloodIssueRepository issueRepository;

    @BeforeEach
    void cleanDatabase() {
        mutableClock.setInstant(BASE_TIME);
        jdbcTemplate.update("DELETE FROM blood_issue");
        jdbcTemplate.update("DELETE FROM release_order");
        jdbcTemplate.update("DELETE FROM test_result_event");
        jdbcTemplate.update("DELETE FROM blood_unit");
    }
}
