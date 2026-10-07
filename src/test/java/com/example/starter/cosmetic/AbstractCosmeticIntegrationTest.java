package com.example.starter.cosmetic;

import com.example.starter.cosmetic.testsupport.MutableClock;
import com.example.starter.cosmetic.testsupport.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * H2 集成测试基类：使用独立内存库 test_cosmetic，注入可控时钟，并在每个用例前清理业务数据。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class AbstractCosmeticIntegrationTest {

    /** 测试固定初始时刻(UTC)。 */
    protected static final Instant INITIAL_NOW = TestClockConfiguration.INITIAL_NOW;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM review_detail");
        jdbc.update("DELETE FROM label_draw");
        jdbc.update("DELETE FROM label_destroy");
        jdbc.update("DELETE FROM packaging_batch");
        jdbc.update("DELETE FROM label_print_run");
        jdbc.update("DELETE FROM product_active_filing");
        jdbc.update("DELETE FROM filing_record");
        jdbc.update("DELETE FROM filing_sequence");
        jdbc.update("INSERT INTO filing_sequence (id, seq) VALUES (1, 0)");
        jdbc.update("DELETE FROM test_report");
        jdbc.update("DELETE FROM child_safety_report");
        clock.setInstant(INITIAL_NOW);
    }
}
