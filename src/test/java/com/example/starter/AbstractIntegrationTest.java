package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /**
     * 数值按规范值比较（整数或小数文本等价，如 10 与 10.000）。
     */
    protected static void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    protected BigDecimal decimal(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }
}
