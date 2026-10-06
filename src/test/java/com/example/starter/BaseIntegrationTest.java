package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：共享应用上下文与独立 H2 命名内存库，
 * 每个用例前清空业务表并重置可控时钟，避免用例间顺序依赖。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:blade_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
@Import(TestTimeConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        clock.reset(TestTimeConfig.BASE_INSTANT);
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM escalation_alert");
        jdbcTemplate.update("DELETE FROM reinspection_record");
        jdbcTemplate.update("DELETE FROM repair_record");
        jdbcTemplate.update("DELETE FROM repair_order");
        jdbcTemplate.update("DELETE FROM curtailment");
        jdbcTemplate.update("DELETE FROM root_cause_evaluation");
        jdbcTemplate.update("DELETE FROM defect");
        jdbcTemplate.update("DELETE FROM inspection_record");
    }

    /** 按叶片编号查台账 ID。 */
    protected long bladeId(String bladeCode) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM blade WHERE blade_code = ?", Long.class, bladeCode);
    }

    protected long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
