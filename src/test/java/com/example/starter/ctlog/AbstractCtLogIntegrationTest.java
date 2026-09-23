package com.example.starter.ctlog;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：使用独立 H2 内存库（ctlog_test），每个用例前清空业务数据，避免顺序依赖。
 */
@SpringBootTest
public abstract class AbstractCtLogIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetTables() {
        jdbc.update("DELETE FROM ct_split_alert");
        jdbc.update("DELETE FROM ct_observer_head");
        jdbc.update("DELETE FROM ct_leaf");
        jdbc.update("UPDATE ct_log_state SET tree_size = 0 WHERE id = 0");
    }
}
