package com.example.starter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
 * 受控分段执行模式端到端 REST 测试：登记、计划查询、整分钟回执、旧回执拦截与错误语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControlledRestIntegrationTest {

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
        jdbc.update("DELETE FROM controlled_minute_confirm");
        jdbc.update("DELETE FROM controlled_plan_step");
        jdbc.update("DELETE FROM controlled_plan_turbine");
        jdbc.update("DELETE FROM controlled_occupation");
        jdbc.update("DELETE FROM controlled_plan");
        jdbc.update("DELETE FROM late_report_event");
        jdbc.update("DELETE FROM alert_event");
        jdbc.update("DELETE FROM execution_report");
        jdbc.update("DELETE FROM allocation");
        jdbc.update("DELETE FROM dispatch_cycle");
        jdbc.update("DELETE FROM curtail_order");
        jdbc.update("DELETE FROM day_ahead_plan_segment");
        jdbc.update("DELETE FROM day_ahead_plan_version");
        jdbc.update("DELETE FROM idempotent_record");
        jdbc.update("DELETE FROM wind_turbine");
        jdbc.update("DELETE FROM wind_farm");
        Instant now = Instant.now();
        jdbc.update("INSERT INTO wind_farm(farm_code, farm_name, created_at) VALUES (?, ?, ?)",
                FARM, "REST 受控测试风电场", now);
        for (Seed s : SEEDS) {
            jdbc.update("""
                    INSERT INTO wind_turbine
                        (farm_code, turbine_code, rated_power_kw, technical_min_kw,
                         available_power_kw, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    FARM, s.code(), s.ratedKw(), Math.round(s.ratedKw() * 0.15f),
                    s.availableKw(), now);
        }
        pin(10, 0, 0);
    }

    private void pin(int hour, int minute, int second) {
        clock.pinAt(LocalDateTime.of(2026, 9, 26, hour, minute, second)
                .toInstant(ZoneOffset.ofHours(8)));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> postEntity(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(base + path, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> getEntity(String path) {
        return rest.getForEntity(base + path, Map.class);
    }

    private Map<String, Object> registerBody(String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("farmCode", FARM);
        body.put("targetPowerKw", 2953);
        body.put("turbines", List.of(
                Map.of("turbineCode", "T-01", "startPowerKw", 2000,
                        "rampRateKwPerMin", 500, "durationMinutes", 3),
                Map.of("turbineCode", "T-02", "startPowerKw", 1000,
                        "rampRateKwPerMin", 100, "durationMinutes", 2),
                Map.of("turbineCode", "T-03", "startPowerKw", 2400,
                        "rampRateKwPerMin", 500, "durationMinutes", 3)));
        return body;
    }

    private Map<String, Object> confirmBody(String requestId, int t1, int t2, int t3) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reports", List.of(
                Map.of("turbineCode", "T-01", "powerKw", t1),
                Map.of("turbineCode", "T-02", "powerKw", t2),
                Map.of("turbineCode", "T-03", "powerKw", t3)));
        return body;
    }

    @Test
    @DisplayName("HTTP 主流程：登记→计划查询→逐分钟回执→完成；旧回执入口 409")
    @SuppressWarnings("unchecked")
    void controlledFlowOverHttp() {
        ResponseEntity<Map> registered = postEntity("/api/curtail/controlled-orders",
                registerBody("http-c1"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = registered.getBody();
        assertThat(body.get("planVersion")).isEqualTo(1);
        assertThat(body.get("durationMinutes")).isEqualTo(3);
        String orderNo = (String) body.get("orderNo");
        List<Map<String, Object>> turbines =
                (List<Map<String, Object>>) body.get("turbines");
        Map<String, Object> t2 = turbines.stream()
                .filter(t -> t.get("turbineCode").equals("T-02")).findFirst().orElseThrow();
        assertThat(t2.get("targetPowerKw")).isEqualTo(800);
        List<Map<String, Object>> t2Steps = (List<Map<String, Object>>) t2.get("steps");
        assertThat(t2Steps.stream().map(s -> s.get("powerKw")).toList())
                .containsExactly(900, 800, 800);

        ResponseEntity<Map> view = getEntity("/api/curtail/controlled-orders/" + orderNo + "/plan");
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody().get("planStatus")).isEqualTo("EXECUTING");
        assertThat(view.getBody().get("confirmedMinute")).isEqualTo(0);

        // 旧回执入口不能绕过受控阶梯：409。
        Map<String, Object> legacyReport = new LinkedHashMap<>();
        legacyReport.put("requestId", "http-legacy-rp");
        legacyReport.put("turbineCode", "T-01");
        legacyReport.put("measuredAt", "2026-09-26T02:00:30Z");
        legacyReport.put("powerKw", 1500);
        ResponseEntity<Map> blocked = postEntity("/api/curtail/reports", legacyReport);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 逐分钟回执：第 1、2 分钟执行中，第 3 分钟完成。
        pin(10, 1, 0);
        ResponseEntity<Map> m1 = postEntity(
                "/api/curtail/controlled-orders/" + orderNo + "/minutes/1/confirm",
                confirmBody("http-cf-1", 1500, 900, 1900));
        assertThat(m1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(m1.getBody().get("executed")).isEqualTo(true);
        assertThat(m1.getBody().get("completed")).isEqualTo(false);

        pin(10, 2, 0);
        postEntity("/api/curtail/controlled-orders/" + orderNo + "/minutes/2/confirm",
                confirmBody("http-cf-2", 1000, 800, 1400));

        pin(10, 3, 0);
        ResponseEntity<Map> m3 = postEntity(
                "/api/curtail/controlled-orders/" + orderNo + "/minutes/3/confirm",
                confirmBody("http-cf-3", 954, 800, 1199));
        assertThat(m3.getBody().get("completed")).isEqualTo(true);
        assertThat(m3.getBody().get("orderStatus")).isEqualTo("COMPLETED");

        ResponseEntity<Map> done = getEntity("/api/curtail/controlled-orders/" + orderNo + "/plan");
        assertThat(done.getBody().get("planStatus")).isEqualTo("COMPLETED");
        assertThat(done.getBody().get("confirmedMinute")).isEqualTo(3);
    }

    @Test
    @DisplayName("HTTP 错误语义：提前执行 409、回执缺项 422、期限不可达 422、未暂停取消 409")
    void errorSemanticsOverHttp() {
        ResponseEntity<Map> registered = postEntity("/api/curtail/controlled-orders",
                registerBody("http-c2"));
        String orderNo = (String) registered.getBody().get("orderNo");

        // 计划时刻未到：409。
        ResponseEntity<Map> early = postEntity(
                "/api/curtail/controlled-orders/" + orderNo + "/minutes/1/confirm",
                confirmBody("http-cf-early", 1500, 900, 1900));
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 缺项：422。
        pin(10, 1, 0);
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("requestId", "http-cf-miss");
        missing.put("reports", List.of(
                Map.of("turbineCode", "T-01", "powerKw", 1500),
                Map.of("turbineCode", "T-02", "powerKw", 900)));
        ResponseEntity<Map> missingResp = postEntity(
                "/api/curtail/controlled-orders/" + orderNo + "/minutes/1/confirm", missing);
        assertThat(missingResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 期限不可达：422（T-02 只给 1 分钟）。
        Map<String, Object> bad = registerBody("http-c3");
        bad.put("turbines", List.of(
                Map.of("turbineCode", "T-01", "startPowerKw", 2000,
                        "rampRateKwPerMin", 500, "durationMinutes", 3),
                Map.of("turbineCode", "T-02", "startPowerKw", 1000,
                        "rampRateKwPerMin", 100, "durationMinutes", 1),
                Map.of("turbineCode", "T-03", "startPowerKw", 2400,
                        "rampRateKwPerMin", 500, "durationMinutes", 3)));
        ResponseEntity<Map> infeasible = postEntity("/api/curtail/controlled-orders", bad);
        assertThat(infeasible.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 未暂停取消：409。
        ResponseEntity<Map> cancel = postEntity(
                "/api/curtail/controlled-orders/" + orderNo + "/cancel",
                Map.of("requestId", "http-cx", "expectedVersion", 1));
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
