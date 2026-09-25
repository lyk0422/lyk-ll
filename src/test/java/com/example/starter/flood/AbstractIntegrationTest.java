package com.example.starter.flood;

import com.example.starter.flood.bootstrap.SyntheticDataInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：每个用例前清空业务表并重新写入合成尾矿库与库容曲线，
 * 保证用例间无顺序依赖；测试库命名为 tailings_test，与本地演示库隔离。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = {
        com.example.starter.StarterApplication.class, TestClockConfig.class })
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected SyntheticDataInitializer dataInitializer;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetDatabaseAndClock() {
        jdbcTemplate.update("DELETE FROM order_event");
        jdbcTemplate.update("DELETE FROM release_order");
        jdbcTemplate.update("DELETE FROM release_plan");
        jdbcTemplate.update("DELETE FROM flood_routing");
        jdbcTemplate.update("DELETE FROM review_record");
        jdbcTemplate.update("DELETE FROM warning_chain");
        jdbcTemplate.update("DELETE FROM late_reading_event");
        jdbcTemplate.update("DELETE FROM rainfall_reading");
        jdbcTemplate.update("DELETE FROM water_reading");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM storage_curve_point");
        jdbcTemplate.update("DELETE FROM pond");
        dataInitializer.init();
        clock.setInstant(TestClockConfig.BASE_INSTANT);
    }
}
