package com.example.starter.roaming;

import com.example.starter.roaming.testsupport.MutableClock;
import com.example.starter.roaming.testsupport.TestClockConfig;
import com.example.starter.StarterApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类：真实 Spring 上下文 + H2(MODE=MySQL)，每例清理业务表、重置可控时钟；
 * 合成基础数据（运营商/车辆/桩）保持不变。所有测试共用同一上下文配置，避免重复初始化内存库。
 */
@SpringBootTest(classes = {StarterApplication.class, TestClockConfig.class})
@AutoConfigureMockMvc
public abstract class AbstractRoamingTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM suspense_write_off");
        jdbc.update("DELETE FROM suspense_account");
        jdbc.update("DELETE FROM settlement_detail");
        jdbc.update("DELETE FROM settlement_batch");
        jdbc.update("DELETE FROM settlement_day_lock");
        jdbc.update("DELETE FROM meter_snapshot");
        jdbc.update("DELETE FROM charging_session");
        jdbc.update("DELETE FROM idempotent_request");
        clock.setInstant(TestClockConfig.BASE);
    }
}
