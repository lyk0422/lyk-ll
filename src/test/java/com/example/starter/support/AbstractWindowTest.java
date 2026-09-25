package com.example.starter.support;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.service.WindowService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试基类：每个用例前清空业务表并重置可控时钟，提供合成数据构造工具。
 */
public abstract class AbstractWindowTest {

    /** 允许靠泊时段起点：2026-10-01T00:00:00Z。 */
    protected static final Instant ALLOWED_START = Instant.parse("2026-10-01T00:00:00Z");
    /** 允许靠泊时段终点：2026-10-03T00:00:00Z。 */
    protected static final Instant ALLOWED_END = Instant.parse("2026-10-03T00:00:00Z");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected WindowService service;

    @Autowired
    protected MutableClock clock;

    protected long berthA;
    protected long berthB;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM window_pilot");
        jdbc.update("DELETE FROM berth_window");
        jdbc.update("DELETE FROM allowed_window");
        jdbc.update("DELETE FROM pilot");
        jdbc.update("DELETE FROM berth");
        clock.set(Instant.parse("2026-09-30T00:00:00Z"));

        berthA = insertBerth("BERTH-A", 12.00);
        berthB = insertBerth("BERTH-B", 8.50);
        insertPilot("PILOT-ALPHA");
        insertPilot("PILOT-BRAVO");
        allowWindow(ALLOWED_START, ALLOWED_END);
    }

    protected long insertBerth(String name, double maxDraft) {
        jdbc.update("INSERT INTO berth (name, max_draft_m) VALUES (?, ?)", name, maxDraft);
        Long id = jdbc.queryForObject("SELECT id FROM berth WHERE name = ?", Long.class, name);
        return id == null ? -1L : id;
    }

    protected void insertPilot(String name) {
        jdbc.update("INSERT INTO pilot (name) VALUES (?)", name);
    }

    protected void allowWindow(Instant start, Instant end) {
        jdbc.update("INSERT INTO allowed_window (start_epoch, end_epoch) VALUES (?, ?)",
                start.getEpochSecond(), end.getEpochSecond());
    }

    protected WindowService.CreateCommand cmd(String requestId, long berthId,
            Instant start, Instant end) {
        return new WindowService.CreateCommand(requestId, "VESSEL-" + requestId, berthId,
                start, end, new BigDecimal("6.00"), 1);
    }

    protected long countWindows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM berth_window", Long.class);
        return count == null ? -1L : count;
    }

    protected static Instant at(String iso) {
        return Instant.parse(iso);
    }
}
