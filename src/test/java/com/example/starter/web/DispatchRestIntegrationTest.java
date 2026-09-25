package com.example.starter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.config.SyntheticDataInitializer;
import com.example.starter.support.ServiceClock;

/**
 * 端到端 REST 测试：真实 HTTP 入口验证接收分摊、幂等/409、回报告警、日结与迟到回报。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DispatchRestIntegrationTest {

    private static final String FARM = SyntheticDataInitializer.FARM_CODE;

    private record Seed(String code, int ratedKw, int availableKw) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("T-01", 2000, 2000),
            new Seed("T-02", 2000, 1600),
            new Seed("T-03", 3000, 2400));

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ServiceClock clock;

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port;
        jdbc.update("DELETE FROM late_report_event");
        jdbc.update("DELETE FROM alert_event");
        jdbc.update("DELETE FROM execution_report");
        jdbc.update("DELETE FROM allocation");
        jdbc.update("DELETE FROM dispatch_cycle");
        jdbc.update("DELETE FROM curtail_order");
        jdbc.update("DELETE FROM idempotent_record");
        jdbc.update("DELETE FROM wind_turbine");
        jdbc.update("DELETE FROM wind_farm");
        Instant now = Instant.now();
        jdbc.update("INSERT INTO wind_farm(farm_code, farm_name, created_at) VALUES (?, ?, ?)",
                FARM, "REST 测试风电场", now);
        for (Seed s : SEEDS) {
            jdbc.update("""
                    INSERT INTO wind_turbine
                        (farm_code, turbine_code, rated_power_kw, technical_min_kw,
                         available_power_kw, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    FARM, s.code(), s.ratedKw(), Math.round(s.ratedKw() * 0.15f),
                    s.availableKw(), now);
        }
        clock.pinAt(LocalDateTime.of(2026, 9, 26, 10, 0, 0)
                .toInstant(ZoneOffset.ofHours(8)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(base + path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> postEntity(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(base + path, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        return rest.getForEntity(base + path, Map.class).getBody();
    }

    private String iso(int hour, int minute, int second) {
        return LocalDateTime.of(2026, 9, 26, hour, minute, second)
                .toInstant(ZoneOffset.ofHours(8)).toString();
    }

    @Test
    @DisplayName("HTTP 主流程：接收分摊→重放→409→回报恰超时告警重分摊→日结→迟到回报→详情复算")
    void fullLifecycleOverHttp() {
        // 1. 接收：全场可用 6000，目标 5000，压减 1000。
        Map<String, Object> order = post("/api/curtail/orders",
                Map.of("requestId", "http-1", "farmCode", FARM, "targetPowerKw", 5000));
        assertThat(order.get("status")).isEqualTo("DISPATCHED");
        assertThat(((Number) order.get("curtailedKw")).intValue()).isEqualTo(1000);
        int allocSum = ((List<Map<String, Object>>) order.get("allocations")).stream()
                .mapToInt(a -> ((Number) a.get("targetPowerKw")).intValue()).sum();
        assertThat(allocSum).isEqualTo(5000);
        String orderNo = (String) order.get("orderNo");

        // 2. 同键同参重放：replayed=true，同一 orderId。
        Map<String, Object> replay = post("/api/curtail/orders",
                Map.of("requestId", "http-1", "farmCode", FARM, "targetPowerKw", 5000));
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("orderId")).isEqualTo(order.get("orderId"));

        // 3. 同键异参：409。
        ResponseEntity<Map> conflict = postEntity("/api/curtail/orders",
                Map.of("requestId", "http-1", "farmCode", FARM, "targetPowerKw", 4999));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 4. 下发后 10 分钟前回报：不告警。
        clock.pinAt(LocalDateTime.of(2026, 9, 26, 10, 9, 59)
                .toInstant(ZoneOffset.ofHours(8)));
        Map<String, Object> early = post("/api/curtail/reports", Map.of(
                "requestId", "rp-early", "turbineCode", "T-01",
                "measuredAt", iso(10, 9, 0), "powerKw", 1900));
        assertThat(early.get("late")).isEqualTo(false);

        // 5. 恰满 10 分钟仍未达标（全场 > 5000）：告警 + 重分摊。
        clock.pinAt(LocalDateTime.of(2026, 9, 26, 10, 10, 0)
                .toInstant(ZoneOffset.ofHours(8)));
        post("/api/curtail/reports", Map.of(
                "requestId", "rp-due", "turbineCode", "T-01",
                "measuredAt", iso(10, 10, 0), "powerKw", 1900));

        Map<String, Object> detailDue = get("/api/curtail/orders/" + orderNo);
        assertThat(((List<?>) detailDue.get("alerts"))).hasSize(1);
        assertThat(((Number) detailDue.get("currentCycleNo")).intValue()).isEqualTo(2);

        // 6. 日结封存。
        Map<String, Object> settle = post("/api/curtail/orders/" + orderNo + "/settle", Map.of());
        assertThat(settle.get("status")).isEqualTo("SETTLED");
        Map<String, Object> settleAgain = post("/api/curtail/orders/" + orderNo + "/settle", Map.of());
        assertThat(settleAgain.get("replayed")).isEqualTo(true);

        // 7. 封存后迟到回报：late=true，仅记迟到事件。
        Map<String, Object> late = post("/api/curtail/reports", Map.of(
                "requestId", "rp-late", "turbineCode", "T-02",
                "measuredAt", iso(10, 30, 0), "powerKw", 1200));
        assertThat(late.get("late")).isEqualTo(true);

        // 8. 详情复算：两轮分摊、执行曲线、一条告警、一条迟到事件。
        Map<String, Object> detail = get("/api/curtail/orders/" + orderNo);
        assertThat(detail.get("status")).isEqualTo("SETTLED");
        assertThat(((List<?>) detail.get("cycles"))).hasSize(2);
        // 执行曲线按机组＋时刻归位（T-01 两条）。
        List<?> curve = (List<?>) detail.get("executionCurve");
        assertThat(curve).hasSize(2);
        Map<String, Object> firstPoint = (Map<String, Object>) curve.get(0);
        assertThat(firstPoint.get("measuredAt")).isEqualTo(iso(10, 9, 0));
        assertThat(((List<?>) detail.get("lateReports"))).hasSize(1);
    }

    @Test
    @DisplayName("参数校验：目标出力非正数返回 400")
    void invalidTargetReturns400() {
        ResponseEntity<Map> resp = postEntity("/api/curtail/orders",
                Map.of("requestId", "http-bad", "farmCode", FARM, "targetPowerKw", 0));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("回报未知机组返回 404")
    void unknownTurbineReturns404() {
        post("/api/curtail/orders",
                Map.of("requestId", "http-2", "farmCode", FARM, "targetPowerKw", 5000));
        ResponseEntity<Map> resp = postEntity("/api/curtail/reports", Map.of(
                "requestId", "rp-x", "turbineCode", "NO-SUCH",
                "measuredAt", iso(10, 1, 0), "powerKw", 100));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
