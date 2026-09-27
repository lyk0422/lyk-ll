package com.example.starter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 日前计划与绑定指令端到端 REST 测试：发布、版本查询、绑定指令与基数证据、
 * 参数 400 / 不存在 404 / 业务拒绝 422 / 冲突 409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanRestIntegrationTest {

    private static final String FARM = SyntheticDataInitializer.FARM_CODE;
    private static final String DATE = "2026-09-27";

    private record Seed(String code, int ratedKw, int availableKw) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("t-01", 2000, 2000),
            new Seed("t-02", 2000, 1600),
            new Seed("t-03", 3000, 2400));

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
        jdbc.update("DELETE FROM order_plan_base");
        jdbc.update("DELETE FROM curtail_order");
        jdbc.update("DELETE FROM day_ahead_plan_slot");
        jdbc.update("DELETE FROM day_ahead_plan");
        jdbc.update("DELETE FROM idempotent_record");
        jdbc.update("DELETE FROM wind_turbine");
        jdbc.update("DELETE FROM wind_farm");
        Instant now = Instant.now();
        jdbc.update("INSERT INTO wind_farm(farm_code, farm_name, created_at) VALUES (?, ?, ?)",
                FARM, "计划 REST 测试风电场", now);
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
    private ResponseEntity<Map> postEntity(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(base + path, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> getEntity(String path) {
        return rest.getForEntity(base + path, Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<List> getList(String path) {
        return rest.getForEntity(base + path, List.class);
    }

    private Map<String, Object> planBody(String requestId, int expectedVersion,
                                         int slotCount, int value) {
        List<Map<String, Object>> slots = new ArrayList<>();
        for (int s = 0; s < slotCount; s++) {
            slots.add(Map.of(
                    "slotIndex", s,
                    "turbines", List.of(
                            Map.of("turbineCode", "t-01", "plannedKw", value),
                            Map.of("turbineCode", "t-02", "plannedKw", value),
                            Map.of("turbineCode", "t-03", "plannedKw", Math.min(value, 3000)))));
        }
        return Map.of(
                "requestId", requestId,
                "farmCode", FARM,
                "planDate", DATE,
                "expectedVersion", expectedVersion,
                "slots", slots);
    }

    @Test
    @DisplayName("HTTP 计划主流程：发布→重放→当前版本/版本列表/详情→绑定指令→基数证据")
    void planLifecycleOverHttp() {
        // 1. 发布 v1。
        ResponseEntity<Map> published = postEntity("/api/curtail/plans",
                planBody("http-plan-1", 0, 96, 1500));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) published.getBody().get("version")).intValue()).isEqualTo(1);
        assertThat(published.getBody().get("current")).isEqualTo(true);
        assertThat(((Number) published.getBody().get("slotCount")).intValue()).isEqualTo(96);
        Object planId = published.getBody().get("planId");

        // 2. 同键同参重放。
        ResponseEntity<Map> replay = postEntity("/api/curtail/plans",
                planBody("http-plan-1", 0, 96, 1500));
        assertThat(replay.getBody().get("replayed")).isEqualTo(true);
        assertThat(replay.getBody().get("planId")).isEqualTo(planId);

        // 3. 当前版本与版本列表。
        ResponseEntity<Map> current = getEntity(
                "/api/curtail/plans/current?farmCode=" + FARM + "&planDate=" + DATE);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) current.getBody().get("version")).intValue()).isEqualTo(1);
        assertThat(((List<?>) current.getBody().get("slots"))).hasSize(96);

        ResponseEntity<List> versions = getList(
                "/api/curtail/plans?farmCode=" + FARM + "&planDate=" + DATE);
        assertThat(versions.getBody()).hasSize(1);

        ResponseEntity<Map> detail = getEntity("/api/curtail/plans/" + planId);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<?>) detail.getBody().get("slots"))).hasSize(96);

        // 4. 绑定指令：可参与 min(可用, 1500/1500/1500) → 1500+1500+1500=4500，目标 4000。
        ResponseEntity<Map> bound = postEntity("/api/curtail/plan-orders", Map.of(
                "requestId", "http-bind-1",
                "farmCode", FARM,
                "targetPowerKw", 4000,
                "planDate", DATE,
                "slotIndex", 0,
                "planVersion", 1));
        assertThat(bound.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bound.getBody().get("status")).isEqualTo("DISPATCHED");
        assertThat(((Number) bound.getBody().get("farmAvailableKw")).intValue()).isEqualTo(4500);
        String orderNo = (String) bound.getBody().get("orderNo");

        // 5. 基数证据。
        ResponseEntity<Map> evidence = getEntity(
                "/api/curtail/orders/" + orderNo + "/plan-binding");
        assertThat(evidence.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(evidence.getBody().get("bound")).isEqualTo(true);
        assertThat(((Number) evidence.getBody().get("planVersion")).intValue()).isEqualTo(1);
        assertThat(((List<?>) evidence.getBody().get("bases"))).hasSize(3);

        // 6. 发布 v2 后 v1 过期：绑定 v1 → 422。
        ResponseEntity<Map> v2 = postEntity("/api/curtail/plans",
                planBody("http-plan-2", 1, 96, 1000));
        assertThat(((Number) v2.getBody().get("version")).intValue()).isEqualTo(2);
        ResponseEntity<Map> stale = postEntity("/api/curtail/plan-orders", Map.of(
                "requestId", "http-bind-stale",
                "farmCode", FARM,
                "targetPowerKw", 3000,
                "planDate", DATE,
                "slotIndex", 0,
                "planVersion", 1));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("HTTP 错误语义：缺段 422、超装机 422、负计划值 400、缺字段 400、版本冲突 409、不存在 404")
    void errorSemanticsOverHttp() {
        // 缺段（95 段）→ 422。
        ResponseEntity<Map> missing = postEntity("/api/curtail/plans",
                planBody("http-bad-95", 0, 95, 1000));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 超装机容量（t-01 额定 2000）→ 422。
        ResponseEntity<Map> overcap = postEntity("/api/curtail/plans",
                planBody("http-bad-cap", 0, 96, 2500));
        assertThat(overcap.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 负计划值 → 400。
        Map<String, Object> negative = planBody("http-bad-neg", 0, 96, -1);
        ResponseEntity<Map> negResp = postEntity("/api/curtail/plans", negative);
        assertThat(negResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 缺 expectedVersion → 400。
        ResponseEntity<Map> noVersion = postEntity("/api/curtail/plans", Map.of(
                "requestId", "http-bad-nover",
                "farmCode", FARM,
                "planDate", DATE,
                "slots", List.of()));
        assertThat(noVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 版本冲突：首发成功后再次 expectedVersion=0 → 409。
        postEntity("/api/curtail/plans", planBody("http-v1", 0, 96, 1000));
        ResponseEntity<Map> conflict = postEntity("/api/curtail/plans",
                planBody("http-v1-again", 0, 96, 1000));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 不存在的计划详情 → 404；无当前版本 → 404；未知风场发布 → 404。
        assertThat(getEntity("/api/curtail/plans/999999").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getEntity("/api/curtail/plans/current?farmCode=" + FARM
                + "&planDate=2026-09-28").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> unknownFarm = planBody("http-nofarm", 0, 96, 1000);
        ResponseEntity<Map> noFarm = postEntity("/api/curtail/plans", Map.of(
                "requestId", "http-nofarm",
                "farmCode", "NO-SUCH-FARM",
                "planDate", DATE,
                "expectedVersion", 0,
                "slots", unknownFarm.get("slots")));
        assertThat(noFarm.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 绑定不存在的版本 → 404；目标不可实现 → 422。
        ResponseEntity<Map> noVer = postEntity("/api/curtail/plan-orders", Map.of(
                "requestId", "http-bind-nover",
                "farmCode", FARM,
                "targetPowerKw", 1000,
                "planDate", DATE,
                "slotIndex", 0,
                "planVersion", 7));
        assertThat(noVer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> infeasible = postEntity("/api/curtail/plan-orders", Map.of(
                "requestId", "http-bind-inf",
                "farmCode", FARM,
                "targetPowerKw", 9999,
                "planDate", DATE,
                "slotIndex", 0,
                "planVersion", 1));
        assertThat(infeasible.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 未知指令的基数证据 → 404。
        assertThat(getEntity("/api/curtail/orders/no-such/plan-binding").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
