package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2(MODE=MySQL) 集成测试基类：每个用例前清空业务表并把阀门状态重置为种子初值，
 * station/flow_meter/odorant_device 合成配置保持不变，保证用例间无顺序依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    private TestTableCleaner cleaner;

    @BeforeEach
    void cleanTables() {
        cleaner.clean();
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
