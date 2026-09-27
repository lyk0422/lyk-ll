package com.example.starter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 新入口端到端 REST 测试：计划发布、版本查询、计划绑定指令受理与基数证据，
 * 覆盖 400/404/409/422 与幂等重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlanRestIntegrationTest {

    private static final String FARM = SyntheticDataInitializer.FARM_CODE;
    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 9, 27);

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
        jdbc.update("DELETE FROM day_ahead_plan_segment");
        jdbc.update("DELETE FROM day_ahead_plan_version");
        jdbc.update("DELETE FROM idempotent_record");
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

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> getEntity(String path) {
        return rest.getForEntity(base + path, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<?> getList(String path) {
        return rest.getForEntity(base + path, List.class).getBody();
    }

    /** 完整 96 段 × 3 机组，所有核定出力 1500 kW（不超装机）。 */
    private List<Map<String, Object>> fullSegments(int powerKw) {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int idx = 0; idx < 96; idx++) {
            for (Seed s : SEEDS) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("segmentIndex", idx);
                m.put("turbineCode", s.code());
                m.put("powerKw", powerKw);
                segments.add(m);
            }
        }
        return segments;
    }

    private Map<String, Object> planBody(String requestId, int expectedVersion, Object segments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("farmCode", FARM);
        body.put("planDate", PLAN_DATE.toString());
        body.put("expectedVersion", expectedVersion);
        body.put("operateAt", "2026-09-26T01:00:00Z");
        body.put("segments", segments);
        return body;
    }

    @Test
    @DisplayName("HTTP 主流程：发布 v1→查询当前/版本列表→发布 v2 替换指针→历史版本仍可读")
    void publishAndQueryVersionsOverHttp() {
        Map<String, Object> v1 = post("/api/plans", planBody("http-plan-1", 1, fullSegments(1500)));
        assertThat(v1.get("versionNo")).isEqualTo(1);
        assertThat(v1.get("current")).isEqualTo(true);
        assertThat(v1.get("segmentCount")).isEqualTo(288);

        Map<String, Object> current = get("/api/plans/" + FARM + "/" + PLAN_DATE + "/versions/current");
        assertThat(current.get("versionNo")).isEqualTo(1);
        assertThat(((List<?>) current.get("segments"))).hasSize(288);

        List<?> versions =
                getList("/api/plans/" + FARM + "/" + PLAN_DATE + "/versions");
        assertThat(versions).hasSize(1);

        Map<String, Object> v2Body = planBody("http-plan-2", 2, fullSegments(1600));
        v2Body.put("operateAt", "2026-09-26T02:00:00Z");
        Map<String, Object> v2 = post("/api/plans", v2Body);
        assertThat(v2.get("versionNo")).isEqualTo(2);

        Map<String, Object> currentAfter =
                get("/api/plans/" + FARM + "/" + PLAN_DATE + "/versions/current");
        assertThat(currentAfter.get("versionNo")).isEqualTo(2);
        Map<String, Object> history =
                get("/api/plans/" + FARM + "/" + PLAN_DATE + "/versions/1");
        assertThat(history.get("current")).isEqualTo(false);
        assertThat(getList(
                "/api/plans/" + FARM + "/" + PLAN_DATE + "/versions")).hasSize(2);
    }

    @Test
    @DisplayName("计划发布同键同参重放；异参 409；expectedVersion 错误 409")
    void publishIdempotentAndConflictOverHttp() {
        Map<String, Object> body = planBody("http-plan-idem", 1, fullSegments(1500));
        post("/api/plans", body);
        Map<String, Object> replay = post("/api/plans", body);
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("versionNo")).isEqualTo(1);

        Map<String, Object> changed = planBody("http-plan-idem", 1, fullSegments(1400));
        assertThat(postEntity("/api/plans", changed).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 已存在 v1 时再以 expectedVersion=1 发布：409。
        assertThat(postEntity("/api/plans", planBody("http-plan-3", 1, fullSegments(1500)))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("缺段整版拒绝：422；时段序号越界：400；风场不存在：404")
    void publishValidationOverHttp() {
        List<Map<String, Object>> missing = new ArrayList<>(fullSegments(1500));
        missing.removeIf(m -> (int) m.get("segmentIndex") == 95);
        assertThat(postEntity("/api/plans", planBody("http-plan-bad", 1, missing)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        List<Map<String, Object>> badIndex = new ArrayList<>(fullSegments(1500));
        badIndex.get(0).put("segmentIndex", 96);
        assertThat(postEntity("/api/plans", planBody("http-plan-idx", 1, badIndex)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> noFarm = planBody("http-plan-nf", 1, fullSegments(1500));
        noFarm.put("farmCode", "NO-FARM");
        assertThat(postEntity("/api/plans", noFarm).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("计划绑定指令：受理→基数证据→过期版本 422→未绑定指令 basis 404")
    void planOrderAndBasisOverHttp() {
        post("/api/plans", planBody("b-plan-1", 1, fullSegments(1500)));

        Map<String, Object> orderBody = new LinkedHashMap<>();
        orderBody.put("requestId", "b-order-1");
        orderBody.put("farmCode", FARM);
        orderBody.put("targetPowerKw", 3000);
        orderBody.put("planDate", PLAN_DATE.toString());
        orderBody.put("segmentIndex", 40);
        orderBody.put("planVersionNo", 1);
        orderBody.put("operateAt", "2026-09-26T02:00:00Z");

        Map<String, Object> order = post("/api/curtail/plan-orders", orderBody);
        assertThat(order.get("status")).isEqualTo("DISPATCHED");
        // 可参与出力 min(实时 2000/1600/2400, 计划 1500)=4500；目标 3000，压减 1500。
        assertThat(order.get("farmParticipatingKw")).isEqualTo(4500);
        assertThat(order.get("curtailedKw")).isEqualTo(1500);
        int targetSum = ((List<Map<String, Object>>) order.get("allocations")).stream()
                .mapToInt(a -> ((Number) a.get("targetPowerKw")).intValue()).sum();
        assertThat(targetSum).isEqualTo(3000);
        String orderNo = (String) order.get("orderNo");

        // 同键同参重放。
        Map<String, Object> replay = post("/api/curtail/plan-orders", orderBody);
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("orderId")).isEqualTo(order.get("orderId"));

        // 基数证据。
        Map<String, Object> basis = get("/api/curtail/orders/" + orderNo + "/basis");
        assertThat(basis.get("planVersionNo")).isEqualTo(1);
        assertThat(basis.get("segmentStart")).isEqualTo("2026-09-27T10:00:00Z");
        assertThat(basis.get("segmentEnd")).isEqualTo("2026-09-27T10:15:00Z");
        List<?> items = (List<?>) ((Map<?, ?>) ((List<?>) basis.get("cycles")).get(0)).get("items");
        assertThat(items).hasSize(3);

        // 发布 v2 后，再绑定 v1：422。（推进服务时钟，避免与已受理指令同接收时刻先撞 409）
        Map<String, Object> v2Body = planBody("b-plan-2", 2, fullSegments(1200));
        v2Body.put("operateAt", "2026-09-26T03:00:00Z");
        post("/api/plans", v2Body);
        clock.pinAt(LocalDateTime.of(2026, 9, 26, 11, 5, 0)
                .toInstant(ZoneOffset.ofHours(8)));
        Map<String, Object> oldBind = new LinkedHashMap<>(orderBody);
        oldBind.put("requestId", "b-order-old");
        oldBind.put("operateAt", "2026-09-26T03:05:00Z");
        assertThat(postEntity("/api/curtail/plan-orders", oldBind).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 已受理指令的证据版本转为非当前，但基数不变。
        Map<String, Object> basisAfter = get("/api/curtail/orders/" + orderNo + "/basis");
        assertThat(basisAfter.get("versionCurrent")).isEqualTo(false);

        // 目标高于可参与出力：422。
        Map<String, Object> infeasible = new LinkedHashMap<>(orderBody);
        infeasible.put("requestId", "b-order-bad");
        infeasible.put("targetPowerKw", 4501);
        infeasible.put("planVersionNo", 2);
        infeasible.put("operateAt", "2026-09-26T03:06:00Z");
        assertThat(postEntity("/api/curtail/plan-orders", infeasible).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 未绑定计划的旧指令无基数证据：404。
        Map<String, Object> legacy = post("/api/curtail/orders",
                Map.of("requestId", "legacy-x", "farmCode", FARM, "targetPowerKw", 4000));
        assertThat(getEntity("/api/curtail/orders/" + legacy.get("orderNo") + "/basis")
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("计划绑定指令参数非法：缺少 operateAt 返回 400")
    void planOrderInvalidReturns400() {
        post("/api/plans", planBody("c-plan-1", 1, fullSegments(1500)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "c-order-1");
        body.put("farmCode", FARM);
        body.put("targetPowerKw", 3000);
        body.put("planDate", PLAN_DATE.toString());
        body.put("segmentIndex", 40);
        body.put("planVersionNo", 1);
        // 无 operateAt
        assertThat(postEntity("/api/curtail/plan-orders", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
