package com.example.starter.irrigation;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：每个测试方法运行前清理业务表，保证场景间无顺序依赖。
 * 使用独立命名内存库 irrigation_test，MODE=MySQL，DB_CLOSE_DELAY=-1，上下文结束释放。
 *
 * <p>不加测试事务：被测服务使用真实事务提交，以支持多线程并发与锁竞争测试。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, FixedClockTestConfig.class})
public abstract class AbstractIntegrationTest {

    /** 清理顺序：先子表后主表。 */
    private static final List<String> TABLES_IN_DELETE_ORDER = List.of(
            "irr_late_meter_event",
            "irr_carryover",
            "irr_settlement",
            "irr_extra_allocation",
            "irr_extra_purchase",
            "irr_meter_event",
            "irr_metering",
            "irr_plan_line",
            "irr_plan_version",
            "irr_group_outlet",
            "irr_outlet",
            "irr_group");

    @Autowired
    protected JdbcTemplate jdbc;

    /** 每个用例前清空全部业务表，避免顺序依赖。 */
    @BeforeEach
    void cleanTablesBeforeEach() {
        cleanAllTables();
    }

    /**
     * 清理全部业务表。
     */
    protected void cleanAllTables() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES_IN_DELETE_ORDER) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }
}
