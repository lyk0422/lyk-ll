package com.example.starter.greenhouse;

import org.junit.jupiter.api.Test;

/**
 * 上下文与 H2（MySQL 模式）建表装配冒烟验证；业务规则由其余测试类覆盖。
 */
class ContextSmokeTest extends BaseGreenhouseTest {

    @Test
    void 上下文启动且建表成功() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'climate_strategy'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }
}
