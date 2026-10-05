package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 滚动演算 HTTP 端到端测试：过程线录入→滚动 v1→恰满 30 分钟滚动 v2（不足提级+追加方案版本）
 * →版本列表/对比/复算→级别轨迹→替代建议确认→归档。
 */
class RestRollingEndToEndTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> resp) {
        return resp.getBody();
    }

    @Test
    @DisplayName("滚动演算全流程 HTTP：恰满 30 分钟触发、不足提级、版本对比复算、建议确认留痕")
    void rollingChainOverHttp() {
        String pond = "TP-DEMO-01";

        // 1. 预警（默认橙色）
        ResponseEntity<Map> warningResp = restTemplate.postForEntity(url("/api/warnings"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-w-1",
                        "pondCode", pond,
                        "title", "HTTP 滚动演算合成预警",
                        "rainSummary", "过程线洪峰 25 m3/s")),
                Map.class);
        assertThat(warningResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String warningNo = (String) body(warningResp).get("warningNo");

        // 2. 录入入库洪水过程线（恰满点取值，峰值 25）
        Map<String, Object> hydroReq = Map.of(
                "requestId", "http2-hg-1",
                "points", List.of(
                        Map.of("forecastTime", "2026-09-25T10:00:00", "inflowRate", "10"),
                        Map.of("forecastTime", "2026-09-25T10:30:00", "inflowRate", "25"),
                        Map.of("forecastTime", "2026-09-25T11:00:00", "inflowRate", "12")));
        ResponseEntity<Map> hydroResp = restTemplate.postForEntity(
                url("/api/warnings/" + warningNo + "/hydrograph"),
                new HttpEntity<>(hydroReq), Map.class);
        assertThat(hydroResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(hydroResp).get("acceptedPoints")).isEqualTo(3);
        // 同键重放
        ResponseEntity<Map> hydroReplay = restTemplate.postForEntity(
                url("/api/warnings/" + warningNo + "/hydrograph"),
                new HttpEntity<>(hydroReq), Map.class);
        assertThat(body(hydroReplay).get("replay")).isEqualTo(true);

        // 3. 静态演算 + 手工方案 v1（20 m3/s）+ 指令
        ResponseEntity<Map> routingResp = restTemplate.postForEntity(url("/api/routings"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-r-1",
                        "pondCode", pond,
                        "warningNo", warningNo,
                        "currentWaterLevel", "200.0000",
                        "designFloodVolume", "400001",
                        "dryBeachLength", "150",
                        "phreaticDepth", "10")),
                Map.class);
        String routingNo = (String) body(routingResp).get("routingNo");
        ResponseEntity<Map> planResp = restTemplate.postForEntity(url("/api/plans"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-p-1",
                        "warningNo", warningNo,
                        "routingNo", routingNo,
                        "openings", List.of(Map.of("name", "1#排洪隧洞工作闸门",
                                "opening", "1.200", "release", "20.000")))),
                Map.class);
        assertThat(planResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderNo = (String) body(planResp).get("orderNo");
        assertThat(body(planResp).get("versionNo")).isEqualTo(1);

        // 4. 滚动 v1（10:00）：能力 20 < 需求 25 → 不足，提级 RED + 追加方案 v2
        Map<String, Object> rollReq = Map.of(
                "requestId", "http2-gd-1",
                "currentWaterLevel", "200.0000",
                "dryBeachLength", "150",
                "phreaticDepth", "10");
        ResponseEntity<Map> roll1 = restTemplate.postForEntity(
                url("/api/warnings/" + warningNo + "/rolling-routings"),
                new HttpEntity<>(rollReq), Map.class);
        assertThat(roll1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(roll1).get("versionNo")).isEqualTo(1);
        assertThat(body(roll1).get("releaseJudgment")).isEqualTo("INSUFFICIENT");
        assertThat(body(roll1).get("warningLevelAfter")).isEqualTo("RED");
        assertThat(body(roll1).get("planVersionAfter")).isEqualTo(2);
        String rollingNo1 = (String) body(roll1).get("rollingNo");

        // 同键重放返回首次版本
        ResponseEntity<Map> rollReplay = restTemplate.postForEntity(
                url("/api/warnings/" + warningNo + "/rolling-routings"),
                new HttpEntity<>(rollReq), Map.class);
        assertThat(body(rollReplay).get("replay")).isEqualTo(true);
        assertThat(body(rollReplay).get("rollingNo")).isEqualTo(rollingNo1);

        // 未满 30 分钟：422 拒绝
        ResponseEntity<Map> early = restTemplate.postForEntity(
                url("/api/warnings/" + warningNo + "/rolling-routings"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-gd-early",
                        "currentWaterLevel", "200.0000")),
                Map.class);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(early).get("code")).isEqualTo("ROLLING_NOT_DUE");

        // 5. 恰满 30 分钟 → 滚动 v2（需求 25 > 新能力 20，仍不足，RED 保持并留痕）
        clock.advanceMinutes(30);
        ResponseEntity<Map> roll2 = restTemplate.postForEntity(
                url("/api/warnings/" + warningNo + "/rolling-routings"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-gd-2",
                        "currentWaterLevel", "200.5000",
                        "dryBeachLength", "150",
                        "phreaticDepth", "10")),
                Map.class);
        assertThat(roll2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(body(roll2).get("versionNo")).isEqualTo(2);
        assertThat(body(roll2).get("releaseJudgment")).isEqualTo("INSUFFICIENT");
        String rollingNo2 = (String) body(roll2).get("rollingNo");

        // 6. 版本列表（旧版本保留可查）与对比
        ResponseEntity<List> versions = restTemplate.getForEntity(
                url("/api/warnings/" + warningNo + "/rolling-routings"), List.class);
        assertThat(versions.getBody()).hasSize(2);
        ResponseEntity<Map> compare = restTemplate.getForEntity(
                url("/api/warnings/" + warningNo
                        + "/rolling-routings/compare?fromVersion=1&toVersion=2"), Map.class);
        assertThat(compare.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(compare).get("warningLevelChange")).isEqualTo("RED->RED");

        // 7. 任一版本单独复算
        ResponseEntity<Map> recompute = restTemplate.getForEntity(
                url("/api/rolling-routings/" + rollingNo1 + "/recompute"), Map.class);
        assertThat(body(recompute).get("consistent")).isEqualTo(true);

        // 8. 级别轨迹留痕
        ResponseEntity<Map> trace = restTemplate.getForEntity(
                url("/api/warnings/" + warningNo + "/level-events"), Map.class);
        assertThat(body(trace).get("currentLevel")).isEqualTo("RED");
        assertThat((List<?>) body(trace).get("events")).isNotEmpty();

        // 9. 在途指令只认 v1；替代建议留痕并人工确认
        ResponseEntity<Map> orderView = restTemplate.getForEntity(
                url("/api/orders/" + orderNo), Map.class);
        assertThat(body(orderView).get("planVersionNo")).isEqualTo(1);

        ResponseEntity<List> suggestions = restTemplate.getForEntity(
                url("/api/orders/" + orderNo + "/suggestions"), List.class);
        assertThat(suggestions.getBody()).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> suggestion =
                (Map<String, Object>) suggestions.getBody().get(0);
        String suggestionNo = (String) suggestion.get("suggestionNo");
        assertThat(suggestion.get("status")).isEqualTo("PENDING");

        ResponseEntity<Map> confirm = restTemplate.postForEntity(
                url("/api/suggestions/" + suggestionNo + "/confirm"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-cf-1",
                        "operator", "调度长",
                        "decision", "KEEP_ORIGINAL_VERSION",
                        "note", "洪峰已过，维持原指令")),
                Map.class);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(confirm).get("status")).isEqualTo("CONFIRMED");
        // 确认同键异参 409
        ResponseEntity<Map> confirmConflict = restTemplate.postForEntity(
                url("/api/suggestions/" + suggestionNo + "/confirm"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-cf-1",
                        "operator", "调度长",
                        "decision", "ADOPT_NEW_VERSION")),
                Map.class);
        assertThat(confirmConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 10. 旧指令按 v1 闭环后归档（终态语义不变）
        restTemplate.postForEntity(url("/api/orders/" + orderNo + "/sign"),
                new HttpEntity<>(Map.of("operator", "值班员甲")), Map.class);
        restTemplate.postForEntity(url("/api/orders/" + orderNo + "/execute"),
                new HttpEntity<>(Map.of("operator", "值班员甲")), Map.class);
        restTemplate.postForEntity(url("/api/orders/" + orderNo + "/receipt"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-rc-1",
                        "operator", "值班员甲",
                        "actualOpenings", List.of(Map.of("name", "1#排洪隧洞工作闸门",
                                "opening", "1.200", "release", "20.000")))),
                Map.class);
        ResponseEntity<Map> archive = restTemplate.postForEntity(url("/api/reviews/archive"),
                new HttpEntity<>(Map.of(
                        "requestId", "http2-rv-1",
                        "warningNo", warningNo,
                        "note", "滚动演算 HTTP 复盘")),
                Map.class);
        assertThat(body(archive).get("status")).isEqualTo("ARCHIVED");
    }
}
