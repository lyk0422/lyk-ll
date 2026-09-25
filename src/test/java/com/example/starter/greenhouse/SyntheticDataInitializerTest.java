package com.example.starter.greenhouse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.starter.greenhouse.support.SyntheticDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 本地启动合成数据初始化器验证：首次写入种子，重复执行保持幂等不重复插入。
 */
@TestPropertySource(properties = "greenhouse.seed-on-startup=true")
class SyntheticDataInitializerTest extends BaseGreenhouseTest {

    @Autowired
    private SyntheticDataInitializer initializer;

    @Test
    void 启动种子写入且重复执行幂等() throws Exception {
        initializer.run();
        Long crops = jdbc.queryForObject("SELECT COUNT(*) FROM crop", Long.class);
        Long zones = jdbc.queryForObject("SELECT COUNT(*) FROM zone", Long.class);
        Long budgets = jdbc.queryForObject("SELECT COUNT(*) FROM energy_budget", Long.class);
        assertEquals(1L, crops);
        assertEquals(1L, zones);
        assertEquals(1L, budgets);

        initializer.run();
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM crop", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM zone", Long.class));
    }
}
