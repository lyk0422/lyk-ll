package com.example.starter.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 测试基类：独立 H2 内存库（MySQL 兼容模式），每个用例前清理业务数据，避免顺序依赖。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseScheduleTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ScheduleService scheduleService;

    @Autowired
    protected IdempotencyService idempotencyService;

    @Autowired
    protected ScheduleRepository repository;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM actual_record");
        jdbc.update("DELETE FROM line_effective_schedule");
        jdbc.update("DELETE FROM roll_change_window");
        jdbc.update("DELETE FROM schedule_item");
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM idempotency_key");
    }
}
