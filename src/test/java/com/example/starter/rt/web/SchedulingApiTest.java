package com.example.starter.rt.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.starter.rt.repository.PlanRepository;

/**
 * 设备与排程 REST 入口端到端测试：真实 HTTP 验证发布/取消/占用查询/受控治疗及
 * 400/404/409/422 错误语义与 UTC 窗口 JSON 序列化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulingApiTest {

    private static final String BASE = "/api/radiotherapy";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PlanRepository planRepository;

    @BeforeEach
    void clean() {
        planRepository.deleteAllForTest();
    }

    private String url(String path) {
        return "http://localhost:" + port + BASE + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private ResponseEntity<Map> post(String path, Map<String, Object> body) {
        return rest.postForEntity(url(path), new HttpEntity<>(body), Map.class);
    }

    /** 通过 HTTP 准备已验证合格计划与等级3设备。 */
    private void setupVerifiedPlanAndDevice() {
        post("/patients", body("patientId", "PH1", "patientName", "HTTP排程患者"));
        post("/constraint-templates", body("templateCode", "TH1", "templateName", "T",
                "items", List.of(body("organCode", "SPINAL_CORD", "organName", "脊髓",
                        "metricType", "DMAX_CGY", "metricRef", null,
                        "limitValue", new BigDecimal("4500.00")))));
        post("/plans", body("patientId", "PH1", "planCode", "PLANH1", "templateCode", "TH1",
                "prescribedTotalDoseCgy", 5000, "fractionDoseCgy", 200, "fractionCount", 25,
                "oarDoses", List.of(body("organCode", "SPINAL_CORD", "metricType", "DMAX_CGY",
                        "metricRef", null, "metricValue", new BigDecimal("4500.00")))));
        post("/audits", body("patientId", "PH1", "planCode", "PLANH1", "versionNo", 1,
                "requestId", "H-AUD"));
        post("/approvals", body("patientId", "PH1", "planCode", "PLANH1", "versionNo", 1,
                "requestId", "H-APV"));
        post("/verifications", body("patientId", "PH1", "planCode", "PLANH1", "versionNo", 1,
                "plannedDoseCgy", 200, "measuredDoseCgy", 200, "requestId", "H-VER"));
        ResponseEntity<Map> device = post("/devices",
                body("deviceId", "DVH1", "deviceName", "HTTP合成加速器",
                        "maxComplexityLevel", 3, "requestId", "H-DEV"));
        assertThat(device.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(device.getBody()).containsEntry("version", 1);
    }

    @Test
    @DisplayName("HTTP排程全链路：发布(冻结)→占用查询→窗口内受控治疗→占用释放实绩冻结")
    void scheduleChainOverHttp() {
        setupVerifiedPlanAndDevice();

        // 发布：窗口包含当前时刻（等级1=10分钟）
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant end = start.plus(10, ChronoUnit.MINUTES);
        ResponseEntity<Map> published = post("/schedules", body(
                "patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DVH1",
                "fractions", List.of(body("fractionNo", 1,
                        "windowStartUtc", start.toString(), "windowEndUtc", end.toString())),
                "requestId", "H-PUB"));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(published.getBody()).containsEntry("level", 1);
        assertThat(published.getBody()).containsEntry("deviceId", "DVH1");
        assertThat(published.getBody()).containsEntry("version", 1);
        Number scheduleId = (Number) published.getBody().get("scheduleId");
        assertThat(scheduleId).isNotNull();

        // 占用查询：1 项 SCHEDULED，窗口 UTC 原样返回
        ResponseEntity<List> occupancy = rest.getForEntity(
                url("/devices/DVH1/occupancy"), List.class);
        assertThat(occupancy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(occupancy.getBody()).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) occupancy.getBody().get(0);
        assertThat(item.get("status")).isEqualTo("SCHEDULED");
        // UTC 窗口经 JSON→H2→JSON 往返后时刻保持不变（毫秒级比对）
        assertThat(Instant.parse((String) item.get("windowStartUtc")).truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(start.truncatedTo(ChronoUnit.MILLIS));

        // 窗口内受控治疗：200，占用释放
        ResponseEntity<Map> treated = post("/treatments", body(
                "patientId", "PH1", "planCode", "PLANH1", "scheduleId", scheduleId,
                "fractionNo", 1, "deliveredDoseCgy", 200, "requestId", "H-TREAT"));
        assertThat(treated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(treated.getBody()).containsEntry("fractionNo", 1);
        assertThat(treated.getBody()).containsEntry("replayed", false);

        ResponseEntity<List> after = rest.getForEntity(url("/devices/DVH1/occupancy"), List.class);
        Map<?, ?> completed = (Map<?, ?>) after.getBody().get(0);
        assertThat(completed.get("status")).isEqualTo("COMPLETED");
        assertThat(completed.get("deliveredDoseCgy")).isEqualTo(200);
    }

    @Test
    @DisplayName("HTTP：窗口长度非法400；设备不存在404；等级超设备能力422；版本不符409")
    void errorSemanticsOverHttp() {
        setupVerifiedPlanAndDevice();

        // 400：窗口 15 分钟 ≠ 等级1的10分钟
        Instant start = Instant.parse("2026-12-01T00:00:00Z");
        ResponseEntity<Map> badWindow = post("/schedules", body(
                "patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DVH1",
                "fractions", List.of(body("fractionNo", 1, "windowStartUtc", start.toString(),
                        "windowEndUtc", start.plus(15, ChronoUnit.MINUTES).toString())),
                "requestId", "H-BAD"));
        assertThat(badWindow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badWindow.getBody()).containsEntry("code", "VALIDATION_FAILED");

        // 404：设备不存在
        ResponseEntity<Map> noDevice = post("/schedules", body(
                "patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DV_NONE",
                "fractions", List.of(body("fractionNo", 1, "windowStartUtc", start.toString(),
                        "windowEndUtc", start.plus(10, ChronoUnit.MINUTES).toString())),
                "requestId", "H-ND"));
        assertThat(noDevice.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(noDevice.getBody()).containsEntry("code", "DEVICE_NOT_FOUND");

        // 422：等级3超过设备能力（先下调到1）
        ResponseEntity<Map> downgrade = post("/devices/DVH1/capability",
                body("maxComplexityLevel", 1, "expectedVersion", 1, "requestId", "H-CAP"));
        assertThat(downgrade.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downgrade.getBody()).containsEntry("version", 2);

        ResponseEntity<Map> incapable = post("/schedules", body(
                "patientId", "PH1", "planCode", "PLANH1", "level", 3, "deviceId", "DVH1",
                "fractions", List.of(body("fractionNo", 1, "windowStartUtc", start.toString(),
                        "windowEndUtc", start.plus(30, ChronoUnit.MINUTES).toString())),
                "requestId", "H-INC"));
        assertThat(incapable.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(incapable.getBody()).containsEntry("code", "DEVICE_CAPABILITY_INSUFFICIENT");

        // 409：能力调整期望版本过期
        ResponseEntity<Map> stale = post("/devices/DVH1/capability",
                body("maxComplexityLevel", 2, "expectedVersion", 1, "requestId", "H-CAP-2"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("code", "VERSION_CONFLICT");
    }
}
