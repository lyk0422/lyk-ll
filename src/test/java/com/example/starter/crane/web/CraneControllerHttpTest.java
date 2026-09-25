package com.example.starter.crane.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.crane.AbstractCraneIntegrationTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 真实 HTTP 入口端到端测试（随机端口 + 嵌入式 H2）：状态码与 JSON 契约。
 */
@org.springframework.boot.test.context.SpringBootTest(webEnvironment =
        org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class CraneControllerHttpTest extends AbstractCraneIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private Map<String, Object> authBody(String requestId, String crane, String zone,
            String start, String end, String load) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("craneCode", crane);
        body.put("zoneCode", zone);
        body.put("startTime", start);
        body.put("endTime", end);
        body.put("loadKg", load);
        return body;
    }

    private Map<String, Object> poseBody(String crane, String ts, String zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("craneCode", crane);
        body.put("ts", ts);
        body.put("zoneCode", zone);
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void full_flow_apply_conflict_pose_brake_release() {
        // 1) 申请授权 201
        ResponseEntity<Map> resp = rest.exchange(url("/api/crane/authorizations"),
                HttpMethod.POST, new HttpEntity<>(authBody("H-1", "C-101", "Z-A1",
                        "2026-09-25T10:00:00Z", "2026-09-25T10:10:00Z", "1000"), jsonHeaders()),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("result")).isEqualTo("GRANTED");
        // 时间字段以 ISO-8601 UTC 字符串返回
        assertThat(resp.getBody().get("startTime")).isEqualTo("2026-09-25T10:00:00Z");

        // 同区同时段另一塔机 → 409
        ResponseEntity<Map> conflict = rest.exchange(url("/api/crane/authorizations"),
                HttpMethod.POST, new HttpEntity<>(authBody("H-2", "C-102", "Z-A1",
                        "2026-09-25T10:00:00Z", "2026-09-25T10:10:00Z", "1000"), jsonHeaders()),
                Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 同键同参回放 → 201（幂等，首次结论）
        ResponseEntity<Map> replay = rest.exchange(url("/api/crane/authorizations"),
                HttpMethod.POST, new HttpEntity<>(authBody("H-1", "C-101", "Z-A1",
                        "2026-09-25T10:00:00Z", "2026-09-25T10:10:00Z", "1000"), jsonHeaders()),
                Map.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().get("replayed")).isEqualTo(true);

        // 参数非法（end<=start）→ 400
        ResponseEntity<Map> bad = rest.exchange(url("/api/crane/authorizations"),
                HttpMethod.POST, new HttpEntity<>(authBody("H-3", "C-101", "Z-A1",
                        "2026-09-25T10:10:00Z", "2026-09-25T10:00:00Z", "1000"), jsonHeaders()),
                Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 2) 未授权塔机 C-102 位姿越限，持续 3 秒后制动
        for (int i = 0; i <= 3; i++) {
            ResponseEntity<Map> pose = rest.exchange(url("/api/crane/poses"),
                    HttpMethod.POST, new HttpEntity<>(poseBody("C-102",
                            "2026-09-25T11:00:0" + i + "Z", "Z-A1"), jsonHeaders()), Map.class);
            assertThat(pose.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<List> warnings = rest.getForEntity(url("/api/crane/warnings?craneCode=C-102"),
                List.class);
        assertThat(warnings.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(warnings.getBody()).hasSize(1);
        Map<?, ?> warning = (Map<?, ?>) warnings.getBody().get(0);
        assertThat(warning.get("status")).isEqualTo("BRAKED");

        ResponseEntity<List> brakes = rest.getForEntity(url("/api/crane/brakes?craneCode=C-102"),
                List.class);
        assertThat(brakes.getBody()).hasSize(1);
        String firstCommand = (String) ((Map<?, ?>) brakes.getBody().get(0)).get("commandCode");

        // 重复取回同一越限事件的制动指令：返回首次指令（制动幂等）
        for (int i = 0; i < 2; i++) {
            ResponseEntity<Map> brakeResp = rest.getForEntity(
                    url("/api/crane/warnings/" + warning.get("id") + "/brake"), Map.class);
            assertThat(brakeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(brakeResp.getBody().get("commandCode")).isEqualTo(firstCommand);
        }
        // 未制动预警查询制动指令 → 404（使用不存在的大 id 模拟“无指令”）
        ResponseEntity<Map> noBrake = rest.getForEntity(
                url("/api/crane/warnings/999999/brake"), Map.class);
        assertThat(noBrake.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 3) 释放授权
        Map<String, Object> releaseBody = Map.of("releaseType", "COMPLETED");
        ResponseEntity<Map> released = rest.exchange(
                url("/api/crane/authorizations/H-1/release"),
                HttpMethod.POST, new HttpEntity<>(releaseBody, jsonHeaders()), Map.class);
        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(released.getBody().get("status")).isEqualTo("RELEASED");

        // 4) 修正留痕
        Map<String, Object> correction = new LinkedHashMap<>();
        correction.put("warningId", warning.get("id"));
        correction.put("reason", "MANUAL_REVIEW");
        correction.put("detail", "HTTP 全流程复核");
        ResponseEntity<Map> corrResp = rest.exchange(url("/api/crane/corrections"),
                HttpMethod.POST, new HttpEntity<>(correction, jsonHeaders()), Map.class);
        assertThat(corrResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknown_request_id_returns_404() {
        ResponseEntity<Map> missing = rest.getForEntity(
                url("/api/crane/authorizations/NO-SUCH"), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
