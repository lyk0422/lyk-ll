package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.arbitration.ArbitrationService;

/**
 * 仲裁测试基类：使用独立 H2 内存库（MySQL 兼容模式），每个用例前清理业务数据并复位时钟，
 * 避免用例间顺序依赖。
 */
@SpringBootTest
@Import(TestClockConfig.class)
public abstract class AbstractArbitrationTest {

    @Autowired
    protected ArbitrationService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM command");
        jdbc.update("DELETE FROM safe_state_event");
        jdbc.update("DELETE FROM claim");
        jdbc.update("UPDATE resource SET holder_claim_id = NULL, holder_controller_id = NULL, "
                + "holder_priority = NULL, safe_applied = FALSE, "
                + "last_command_seq = NULL, last_command_ts = NULL");
        jdbc.update("UPDATE estop_state SET active = FALSE, updated_at = 0 WHERE id = 1");
        clock.setMillis(TestClockConfig.T0);
    }
}
