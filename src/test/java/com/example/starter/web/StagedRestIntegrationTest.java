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
 * 端到端 REST 测试：受控分段执行模式 HTTP 入口——登记、分钟回执（提前/跳号/缺项）、
 * 完成指令、旧回执拦截与计划详情。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StagedRestIntegrationTest {

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
        jdbc.update("DELETE FROM staged_minute_confirm");
        jdbc.update("DELETE FROM staged_plan_step");
        jdbc.update("DELETE FROM staged_plan_turbine");
        jdbc.update("DELETE FROM staged_plan");
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
                FARM, "REST 分段测试风电场", now);
        for (Seed s : SEEDS) {
            jdbc.update("""
                    INSERT INTO wind_turbine
                        (farm_code, turbine_code, rated_power_kw, technical_min_kw,
                         available_power_kw, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    FARM, s.code(), s.ratedKw(), Math.round(s.ratedKw() * 0.15f),
                    s.availableKw(), now);
        }
        // T-01 可用 1000、T-02/T-03 压到技术下限：目标 1550 时仅 T-01 压减到 800。
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 1000 WHERE turbine_code = 'T-01'");
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 300 WHERE turbine_code = 'T-02'");
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 450 WHERE turbine_code = 'T-03'");
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

    private Map<String, Object> registerBody(String requestId) {
        return Map.of(
                "requestId", requestId,
                "farmCode", FARM,
                "targetPowerKw", 1550,
                "operateAt", iso(10, 0, 0),
                "turbines", List.of(Map.of(
                        "turbineCode", "T-01",
                        "startPowerKw", 1000,
                        "rampKwPerMin", 100,
                        "deadlineMinutes", 2)));
    }

    @Test
    @DisplayName("HTTP 分段主流程：登记→提前 409→第 1 分钟→跳号 409→第 2 分钟完成→旧回执恢复")
    void stagedLifecycleOverHttp() {
        // 登记：1000→800、上限 100kW/分钟、期限 2 分钟。
        Map<String, Object> order = post("/api/curtail/staged-orders", registerBody("http-so-1"));
        assertThat(order.get("planStatus")).isEqualTo("EXECUTING");
        assertThat(((Number) order.get("planVersion")).intValue()).isEqualTo(1);
        assertThat(((Number) order.get("durationMinutes")).intValue()).isEqualTo(2);
        String orderNo = (String) order.get("orderNo");

        // 占用中旧回执：409。
        ResponseEntity<Map> blocked = postEntity("/api/curtail/reports", Map.of(
                "requestId", "rp-blocked", "turbineCode", "T-01",
                "measuredAt", iso(10, 0, 30), "powerKw", 900));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 提前执行第 1 分钟（执行时刻 10:01:00）：409。
        ResponseEntity<Map> early = postEntity("/api/curtail/staged-receipts", Map.of(
                "requestId", "sr-early", "orderNo", orderNo, "minuteNo", 1,
                "items", List.of(Map.of("turbineCode", "T-01", "powerKw", 900))));
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 第 1 分钟：10:01:00 确认 900。
        clock.pinAt(LocalDateTime.of(2026, 9, 26, 10, 1, 0)
                .toInstant(ZoneOffset.ofHours(8)));
        Map<String, Object> r1 = post("/api/curtail/staged-receipts", Map.of(
                "requestId", "sr-http-1", "orderNo", orderNo, "minuteNo", 1,
                "items", List.of(Map.of("turbineCode", "T-01", "powerKw", 900))));
        assertThat(r1.get("executed")).isEqualTo(true);
        assertThat(r1.get("completed")).isEqualTo(false);

        // 跳号确认第 2 分钟前的重复序号：409（须为 2）。
        ResponseEntity<Map> dup = postEntity("/api/curtail/staged-receipts", Map.of(
                "requestId", "sr-dup", "orderNo", orderNo, "minuteNo", 1,
                "items", List.of(Map.of("turbineCode", "T-01", "powerKw", 900))));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 缺项（items 为空以外的缺机组场景用缺功率值代替：功率不等于阶梯 422）。
        clock.pinAt(LocalDateTime.of(2026, 9, 26, 10, 2, 0)
                .toInstant(ZoneOffset.ofHours(8)));
        ResponseEntity<Map> wrong = postEntity("/api/curtail/staged-receipts", Map.of(
                "requestId", "sr-wrong", "orderNo", orderNo, "minuteNo", 2,
                "items", List.of(Map.of("turbineCode", "T-01", "powerKw", 801))));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 第 2 分钟（最后）：确认 800 → 指令完成。
        Map<String, Object> r2 = post("/api/curtail/staged-receipts", Map.of(
                "requestId", "sr-http-2", "orderNo", orderNo, "minuteNo", 2,
                "items", List.of(Map.of("turbineCode", "T-01", "powerKw", 800))));
        assertThat(r2.get("completed")).isEqualTo(true);

        // 计划详情：COMPLETED，两分钟均已确认。
        Map<String, Object> plan = get("/api/curtail/staged-orders/" + orderNo + "/plan");
        assertThat(plan.get("planStatus")).isEqualTo("COMPLETED");
        assertThat(((Number) plan.get("currentMinute")).intValue()).isEqualTo(2);
        List<?> minutes = (List<?>) plan.get("minutes");
        assertThat(minutes).hasSize(2);
        assertThat(((Map<?, ?>) minutes.get(0)).get("confirmed")).isEqualTo(true);

        // 指令终态 COMPLETED；占用已释放，旧回执记为迟到事件（不再 409）。
        Map<String, Object> detail = get("/api/curtail/orders/" + orderNo);
        assertThat(detail.get("status")).isEqualTo("COMPLETED");
        Map<String, Object> late = post("/api/curtail/reports", Map.of(
                "requestId", "rp-late", "turbineCode", "T-01",
                "measuredAt", iso(10, 3, 0), "powerKw", 800));
        assertThat(late.get("late")).isEqualTo(true);
    }

    @Test
    @DisplayName("HTTP 失败分支：期限不足 422、缺项 422、未启用指令回执 404")
    void stagedFailureBranchesOverHttp() {
        // 期限不足：上限 50、期限 2（需 4 分钟）→ 422。
        ResponseEntity<Map> infeasible = postEntity("/api/curtail/staged-orders", Map.of(
                "requestId", "http-so-bad", "farmCode", FARM, "targetPowerKw", 1550,
                "operateAt", iso(10, 0, 0),
                "turbines", List.of(Map.of(
                        "turbineCode", "T-01", "startPowerKw", 1000,
                        "rampKwPerMin", 50, "deadlineMinutes", 2))));
        assertThat(infeasible.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Map<String, Object> order = post("/api/curtail/staged-orders", registerBody("http-so-2"));
        String orderNo = (String) order.get("orderNo");

        // 缺项：items 缺少参与机组以外的机组（未知机组 400 与缺项 422 取其一验证缺项语义）。
        clock.pinAt(LocalDateTime.of(2026, 9, 26, 10, 1, 0)
                .toInstant(ZoneOffset.ofHours(8)));
        ResponseEntity<Map> missing = postEntity("/api/curtail/staged-receipts", Map.of(
                "requestId", "sr-miss", "orderNo", orderNo, "minuteNo", 1,
                "items", List.of(Map.of("turbineCode", "T-03", "powerKw", 450))));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 未启用分段模式的旧指令：回执 404。
        Map<String, Object> legacy = post("/api/curtail/orders",
                Map.of("requestId", "http-legacy", "farmCode", FARM, "targetPowerKw", 1500));
        ResponseEntity<Map> notFound = postEntity("/api/curtail/staged-receipts", Map.of(
                "requestId", "sr-legacy", "orderNo", (String) legacy.get("orderNo"), "minuteNo", 1,
                "items", List.of(Map.of("turbineCode", "T-01", "powerKw", 900))));
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
