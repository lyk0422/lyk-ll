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
 * 日前计划与计划绑定指令 REST 端到端测试：真实 HTTP 校验 200/400/404/409/422 状态映射、
 * 版本查询与绑定基数证据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanRestIntegrationTest {

    private static final String FARM = SyntheticDataInitializer.FARM_CODE;
    private static final String DATE = "2026-09-27";

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
        jdbc.update("DELETE FROM order_plan_basis");
        jdbc.update("DELETE FROM allocation");
        jdbc.update("DELETE FROM dispatch_cycle");
        jdbc.update("DELETE FROM curtail_order");
        jdbc.update("DELETE FROM idempotent_record");
        jdbc.update("DELETE FROM day_ahead_plan_entry");
        jdbc.update("DELETE FROM day_ahead_plan");
        jdbc.update("DELETE FROM wind_turbine");
        jdbc.update("DELETE FROM wind_farm");
        Instant now = Instant.now();
        jdbc.update("INSERT INTO wind_farm(farm_code, farm_name, created_at) VALUES (?, ?, ?)",
                FARM, "REST 计划测试风电场", now);
        for (Seed s : SEEDS) {
            jdbc.update("""
                    INSERT INTO wind_turbine
                        (farm_code, turbine_code, rated_power_kw, technical_min_kw,
                         available_power_kw, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    FARM, s.code(), s.ratedKw(), Math.round(s.ratedKw() * 0.15f),
                    s.availableKw(), now);
        }
        clock.pinAt(LocalDateTime.of(2026, 9, 27, 10, 0, 0)
                .toInstant(ZoneOffset.ofHours(8)));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(base + path, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postOk(String path, Object body) {
        return post(path, body).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        return rest.getForEntity(base + path, Map.class).getBody();
    }

    private List<Map<String, Object>> segments(int t1, int t2, int t3) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            result.add(Map.of("segmentIndex", i, "entries", List.of(
                    Map.of("turbineCode", "T-01", "plannedPowerKw", t1),
                    Map.of("turbineCode", "T-02", "plannedPowerKw", t2),
                    Map.of("turbineCode", "T-03", "plannedPowerKw", t3))));
        }
        return result;
    }

    private Map<String, Object> publishBody(String requestId, int expected, int t1, int t2, int t3) {
        return Map.of(
                "requestId", requestId,
                "farmCode", FARM,
                "planDate", DATE,
                "expectedVersion", expected,
                "opTime", "2026-09-26T01:00:00Z",
                "segments", segments(t1, t2, t3));
    }

    @Test
    @DisplayName("HTTP 计划主流程：发布 v1→查询→发布 v2 原子替换→绑定指令→指令基数证据")
    void planPublishQueryAndBindOverHttp() {
        Map<String, Object> v1 = postOk("/api/curtail/plans/versions",
                publishBody("h-pub-1", 0, 1500, 1800, 2400));
        assertThat(v1.get("versionNo")).isEqualTo(1);
        assertThat(v1.get("current")).isEqualTo(true);
        Number planId = (Number) v1.get("planId");

        Map<String, Object> versions = get(
                "/api/curtail/plans/versions?farmCode=" + FARM + "&planDate=" + DATE);
        assertThat(versions.get("currentVersionNo")).isEqualTo(1);
        assertThat(((List<?>) versions.get("versions"))).hasSize(1);

        Map<String, Object> detail = get("/api/curtail/plans/" + planId.intValue());
        assertThat(detail.get("versionNo")).isEqualTo(1);
        assertThat(((List<?>) detail.get("segments"))).hasSize(96);

        // 发布 v2。
        Map<String, Object> v2 = postOk("/api/curtail/plans/versions",
                publishBody("h-pub-2", 1, 1400, 1700, 2300));
        assertThat(v2.get("versionNo")).isEqualTo(2);
        Map<String, Object> versions2 = get(
                "/api/curtail/plans/versions?farmCode=" + FARM + "&planDate=" + DATE);
        assertThat(versions2.get("currentVersionNo")).isEqualTo(2);
        assertThat(((List<?>) versions2.get("versions"))).hasSize(2);
        // 旧版本变为历史。
        Map<String, Object> oldDetail = get("/api/curtail/plans/" + planId.intValue());
        assertThat(oldDetail.get("current")).isEqualTo(false);

        // 绑定当前版本 2 指令（02:30 UTC=segment 10；参与 min:1400/1600/2300=5300，目标 4000）。
        Map<String, Object> order = postOk("/api/curtail/plans/orders", Map.of(
                "requestId", "h-po-1",
                "farmCode", FARM,
                "targetPowerKw", 4000,
                "planDate", DATE,
                "segmentIndex", 10,
                "expectedVersion", 2,
                "opTime", "2026-09-27T02:30:00Z"));
        assertThat(order.get("status")).isEqualTo("DISPATCHED");
        assertThat(order.get("participatingTotalKw")).isEqualTo(5300);
        List<?> basis = (List<?>) order.get("basis");
        assertThat(basis).hasSize(3);
        String orderNo = (String) order.get("orderNo");

        // 指令详情含绑定证据。
        Map<String, Object> orderDetail = get("/api/curtail/orders/" + orderNo);
        Map<?, ?> binding = (Map<?, ?>) orderDetail.get("planBinding");
        assertThat(binding.get("versionNo")).isEqualTo(2);
        assertThat(((List<?>) orderDetail.get("frozenBasis"))).hasSize(3);
    }

    @Test
    @DisplayName("缺段整版拒绝返回 422，且同键可重试成功")
    void incompletePlanReturns422() {
        List<Map<String, Object>> segs = segments(1500, 1800, 2400);
        segs.remove(50);
        Map<String, Object> body = Map.of(
                "requestId", "h-bad",
                "farmCode", FARM,
                "planDate", DATE,
                "expectedVersion", 0,
                "opTime", "2026-09-26T01:00:00Z",
                "segments", segs);
        ResponseEntity<Map> resp = post("/api/curtail/plans/versions", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 失败不占键：同键发布合法计划成功。
        ResponseEntity<Map> retry = post("/api/curtail/plans/versions",
                publishBody("h-bad", 0, 1500, 1800, 2400));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("超装机整版拒绝 422；expectedVersion 过期 409；非法日期 400；版本不存在 404")
    void mixedStatusMappings() {
        // 超装机 T-03 额定 3000 → 3001 整版 422。
        assertThat(post("/api/curtail/plans/versions",
                publishBody("h-cap", 0, 1500, 1800, 3001)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        postOk("/api/curtail/plans/versions", publishBody("h-base", 0, 1500, 1800, 2400));
        // 当前为 v1，仍传 expectedVersion=0 → 409。
        assertThat(post("/api/curtail/plans/versions",
                publishBody("h-stale", 0, 1500, 1800, 2400)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // 非法日期 → 400。
        Map<String, Object> badDate = Map.of(
                "requestId", "h-date", "farmCode", FARM, "planDate", "2026/09/27",
                "expectedVersion", 0, "opTime", "2026-09-26T01:00:00Z",
                "segments", segments(1500, 1800, 2400));
        assertThat(post("/api/curtail/plans/versions", badDate).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // 版本不存在 → 404。
        ResponseEntity<Map> missing = rest.getForEntity(
                base + "/api/curtail/plans/999999", Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("绑定过期历史版本 422；绑定不可实现目标 422；绑定同键同参重放 replayed=true")
    void bindStaleVersionAndUnachievableReturn422() {
        postOk("/api/curtail/plans/versions", publishBody("h-b1", 0, 1500, 1800, 2400));
        postOk("/api/curtail/plans/versions", publishBody("h-b2", 1, 1400, 1700, 2300));

        // 当前 v2，绑定 v1 → 422。
        ResponseEntity<Map> stale = post("/api/curtail/plans/orders", Map.of(
                "requestId", "h-po-stale", "farmCode", FARM, "targetPowerKw", 4000,
                "planDate", DATE, "segmentIndex", 10, "expectedVersion", 1,
                "opTime", "2026-09-27T02:30:00Z"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 目标高于可参与（v2 参与合计 5300）→ 422。
        ResponseEntity<Map> impossible = post("/api/curtail/plans/orders", Map.of(
                "requestId", "h-po-imp", "farmCode", FARM, "targetPowerKw", 5301,
                "planDate", DATE, "segmentIndex", 10, "expectedVersion", 2,
                "opTime", "2026-09-27T02:30:00Z"));
        assertThat(impossible.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 合法绑定后同键同参重放。
        Object okBody = Map.of(
                "requestId", "h-po-ok", "farmCode", FARM, "targetPowerKw", 4000,
                "planDate", DATE, "segmentIndex", 10, "expectedVersion", 2,
                "opTime", "2026-09-27T02:31:00Z");
        Map<String, Object> first = postOk("/api/curtail/plans/orders", okBody);
        Map<String, Object> replay = postOk("/api/curtail/plans/orders", okBody);
        assertThat(first.get("replayed")).isEqualTo(false);
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("orderId")).isEqualTo(first.get("orderId"));
    }
}
