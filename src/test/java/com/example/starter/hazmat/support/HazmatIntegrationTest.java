package com.example.starter.hazmat.support;

import com.example.starter.hazmat.repo.RouteRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试基类：真实 Spring 上下文 + H2（MODE=MySQL）；每个用例前清空业务表
 * （保留路线主数据），并将可控时钟重置到固定基准时刻，避免用例间顺序依赖。
 */
@SpringBootTest
public abstract class HazmatIntegrationTest {

    /** 基准时刻对应的本地时间（Asia/Shanghai = UTC+8）：2026-06-01T08:00。 */
    protected static final LocalDateTime BASE_TIME =
            LocalDateTime.ofInstant(TestClockConfiguration.BASE_INSTANT, ZoneOffset.ofHours(8));

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected RouteRepository routes;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM checkin_record");
        jdbc.update("DELETE FROM alert");
        jdbc.update("DELETE FROM vehicle_freeze");
        jdbc.update("DELETE FROM reroute_request");
        jdbc.update("DELETE FROM waybill_waypoint");
        jdbc.update("DELETE FROM waybill");
        clock.setInstant(TestClockConfiguration.BASE_INSTANT);
    }

    protected long routeId(String code) {
        return routes.findByCode(code).orElseThrow().id();
    }
}
