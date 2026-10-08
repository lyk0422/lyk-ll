package com.example.starter;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.support.AppClock;

/**
 * 测试基类：独立 H2 内存库，每个用例前清理业务数据并重置时钟；班组与重要用户为种子合成数据。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractRepairTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AppClock appClock;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM gen_idempotency");
        jdbc.update("DELETE FROM replacement_switch_evidence");
        jdbc.update("DELETE FROM replacement_confirmation");
        jdbc.update("DELETE FROM replacement_truck");
        jdbc.update("DELETE FROM standby_replacement");
        jdbc.update("DELETE FROM supply_evidence");
        jdbc.update("DELETE FROM active_occupation");
        jdbc.update("DELETE FROM dispatch_truck");
        jdbc.update("DELETE FROM dispatch_load");
        jdbc.update("DELETE FROM generator_dispatch");
        jdbc.update("DELETE FROM generator_truck");
        jdbc.update("INSERT INTO generator_truck (capacity_kw, feeder_id, available, version, created_at)"
                + " VALUES (200,'F01',TRUE,1,CURRENT_TIMESTAMP),"
                + " (300,'F01',TRUE,1,CURRENT_TIMESTAMP),"
                + " (150,'F02',TRUE,1,CURRENT_TIMESTAMP)");
        jdbc.update("DELETE FROM late_report_event");
        jdbc.update("DELETE FROM generator_event");
        jdbc.update("DELETE FROM load_recovery");
        jdbc.update("DELETE FROM restore_confirmation");
        jdbc.update("DELETE FROM dispatch_record");
        jdbc.update("DELETE FROM fault_report");
        jdbc.update("DELETE FROM work_order_event");
        jdbc.update("DELETE FROM work_order");
        appClock.reset();
    }

    /**
     * 将应用时钟固定到指定本地时间（Asia/Shanghai）。
     */
    protected void freezeAt(LocalDateTime time) {
        appClock.setClock(Clock.fixed(time.atZone(AppClock.ZONE).toInstant(), AppClock.ZONE));
    }

    /**
     * 按班组名称查询班组ID。
     */
    protected long teamId(String name) {
        return jdbc.queryForObject("SELECT id FROM repair_team WHERE name = ?", Long.class, name);
    }

    /**
     * 查询工单状态。
     */
    protected String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM work_order WHERE id = ?", String.class, orderId);
    }

    /**
     * 统计表行数。
     */
    protected int count(String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where,
                Integer.class, args);
        return n == null ? 0 : n;
    }
}
