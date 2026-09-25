package com.example.starter.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 集成测试基类：使用独立 H2 内存库（MODE=MySQL），每个用例前清理业务数据，避免顺序依赖。
 * 核素种子表保留（半衰期参数唯一来源）。
 */
@SpringBootTest
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcClient jdbc;

    /**
     * 清理业务表（按外键依赖顺序），保留核素种子数据。
     */
    @BeforeEach
    void cleanBusinessTables() {
        jdbc.sql("DELETE FROM measurement").update();
        jdbc.sql("DELETE FROM ledger").update();
        jdbc.sql("DELETE FROM delivery").update();
        jdbc.sql("DELETE FROM dispense_check").update();
        jdbc.sql("DELETE FROM dispense").update();
        jdbc.sql("DELETE FROM daily_check").update();
        jdbc.sql("DELETE FROM mother_liquor").update();
        jdbc.sql("DELETE FROM reference_source").update();
        jdbc.sql("DELETE FROM dose_calibrator").update();
    }

    /**
     * 查询合成核素 TEST-1H（半衰期 3600 秒）主键，便于整点复算。
     */
    protected long testNuclideId() {
        return jdbc.sql("SELECT id FROM nuclide WHERE name = 'TEST-1H'")
                .query(Long.class).single();
    }
}
