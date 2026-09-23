package com.example.starter.calibration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2（MODE=MySQL）集成测试基类：每个场景前清空业务表，避免顺序依赖。
 */
@SpringBootTest
public abstract class AbstractH2Test {

    @Autowired
    protected JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM canary_vehicle");
        jdbc.update("DELETE FROM canary_vehicle_lock");
        jdbc.update("DELETE FROM canary_release");
        jdbc.update("DELETE FROM resolution_record");
        jdbc.update("DELETE FROM status_transition");
        jdbc.update("DELETE FROM calibration_version");
    }
}
