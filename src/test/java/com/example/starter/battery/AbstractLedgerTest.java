package com.example.starter.battery;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 台账测试基类：共享 Spring 上下文与可控时钟，每个用例前清空全部台账数据。
 */
@SpringBootTest
@Import(TestClockConfig.class)
abstract class AbstractLedgerTest {

    @Autowired
    protected BatteryLedgerService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected AlarmRepository alarmRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM alarm");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM battery_event");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM battery");
    }

    /** 统计某电池的事件数。 */
    protected int eventCount(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM battery_event WHERE battery_code = ?", Integer.class, code);
        return count == null ? 0 : count;
    }

    /** 查询电池当前状态。 */
    protected String batteryStatus(String code) {
        return jdbc.queryForObject("SELECT status FROM battery WHERE code = ?",
                String.class, code);
    }

    /** 查询调拨单状态。 */
    protected String transferStatus(long transferId) {
        return jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?",
                String.class, transferId);
    }
}
