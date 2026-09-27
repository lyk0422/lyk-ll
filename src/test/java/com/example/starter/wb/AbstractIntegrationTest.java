package com.example.starter.wb;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 集成测试基类：用例间清理业务数据表，保留飞机/包线/站位/航班合成主数据，
 * 避免顺序依赖；所有用例共享同一隔离的内存库 loadsheet_test。
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanBusinessTables() {
        jdbc.execute("DELETE FROM cargo_uld_occupancy");
        jdbc.execute("DELETE FROM cargo_loading_item");
        jdbc.execute("DELETE FROM cargo_loading_plan");
        jdbc.execute("DELETE FROM load_detail");
        jdbc.execute("DELETE FROM review_confirmation");
        jdbc.execute("DELETE FROM lmc_log");
        jdbc.execute("DELETE FROM late_event");
        jdbc.execute("DELETE FROM manifest_version");
        jdbc.execute("DELETE FROM idempotent_request");
    }
}
