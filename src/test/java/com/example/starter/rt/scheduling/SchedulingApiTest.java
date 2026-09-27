package com.example.starter.rt.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.starter.rt.repository.PlanRepository;

/**
 * 排程门禁真实 HTTP 端到端测试：设备登记/下调、排程发布/取消/查询与受控分次执行全链路，
 * 以及 400/404/409/422 在 REST 层的真实表现。使用系统时钟，窗口围绕当前 UTC 时刻构造。
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> verifiedPlanFixture(String patientId, String planCode,
                                                    String templateCode, String deviceId) {
        assertThat(post("/patients", body("patientId", patientId, "patientName", "HTTP排程患者"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/constraint-templates", body(
                "templateCode", templateCode, "templateName", "T",
                "items", List.of(body("organCode", "SPINAL_CORD", "organName", "脊髓",
                        "metricType", "DMAX_CGY", "metricRef", null,
                        "limitValue", new BigDecimal("4500.00"))))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post("/plans", body(
                "patientId", patientId, "planCode", planCode, "templateCode", templateCode,
                "prescribedTotalDoseCgy", 5000, "fractionDoseCgy", 200, "fractionCount", 25,
                "oarDoses", List.of(body("organCode", "SPINAL_CORD", "metricType", "DMAX_CGY",
                        "metricRef", null, "metricValue", new BigDecimal("4500.00")))))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/audits", body("patientId", patientId, "planCode", planCode,
                "versionNo", 1, "requestId", patientId + "-AUD")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/approvals", body("patientId", patientId, "planCode", planCode,
                "versionNo", 1, "requestId", patientId + "-APV")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/verifications", body("patientId", patientId, "planCode", planCode,
                "versionNo", 1, "plannedDoseCgy", 200, "measuredDoseCgy", 200,
                "requestId", patientId + "-VER")).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> device = rest.postForEntity(url("/devices"),
                new HttpEntity<>(body("deviceId", deviceId, "deviceName", "加速器",
                        "capabilityLevel", 3)), Map.class);
        assertThat(device.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(device.getBody()).containsEntry("version", 1);
        return device.getBody();
    }

    @Test
    @DisplayName("HTTP全链路：发布排程→窗口内受控分次执行→占用释放，查询状态为EXECUTED")
    void publishExecuteAndQueryOverHttp() {
        String patient = "PH1";
        String plan = "PLANH1";
        verifiedPlanFixture(patient, plan, "TH1", "DEVH1");

        // 围绕当前 UTC 时刻构造恰 10 分钟窗口，保证受控执行落在半开区间内
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        OffsetDateTime start = now.minusMinutes(2);
        OffsetDateTime end = start.plusMinutes(10);
        Map<String, Object> publish = body(
                "patientId", patient, "planCode", plan, "versionNo", 1, "requestId", "H-PUB",
                "items", List.of(body("fractionNo", 1, "deviceId", "DEVH1",
                        "complexityLevel", 1, "windowStartUtc", start, "windowEndUtc", end)));
        ResponseEntity<Map> published = post("/schedules", publish);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> schedules = (List<?>) published.getBody().get("schedules");
        assertThat(schedules).hasSize(1);
        Number scheduleId = (Number) ((Map<?, ?>) schedules.get(0)).get("id");

        // 设备占用查询命中
        ResponseEntity<Map[]> occ = rest.getForEntity(
                url("/devices/DEVH1/occupancies?fromUtc={f}&toUtc={t}"),
                Map[].class, now.minusMinutes(10), now.plusMinutes(30));
        assertThat(occ.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(occ.getBody()).hasSize(1);

        // 窗口内受控分次执行
        ResponseEntity<Map> fraction = post("/fractions", body(
                "patientId", patient, "planCode", plan, "fractionNo", 1,
                "deliveredDoseCgy", 200, "requestId", "H-FR"));
        assertThat(fraction.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fraction.getBody()).containsEntry("fractionNo", 1);

        // 占用已释放；排程状态 EXECUTED
        ResponseEntity<Map[]> booked = rest.getForEntity(
                url("/devices/DEVH1/occupancies"), Map[].class);
        assertThat(booked.getBody()).isEmpty();
        ResponseEntity<Map[]> planSchedules = rest.getForEntity(
                url("/patients/" + patient + "/plans/" + plan + "/schedules"), Map[].class);
        assertThat(planSchedules.getBody()).hasSize(1);
        assertThat(planSchedules.getBody()[0]).containsEntry("status", "EXECUTED");
        assertThat(planSchedules.getBody()[0]).containsEntry("version", 2);
        assertThat(planSchedules.getBody()[0]).containsKey("executedFractionId");
    }

    @Test
    @DisplayName("HTTP：发布同键重放200 replayed=true；异参409；参数非法400；设备不存在404")
    void publishReplayConflictAndValidationOverHttp() {
        String patient = "PH2";
        String plan = "PLANH2";
        verifiedPlanFixture(patient, plan, "TH2", "DEVH2");

        OffsetDateTime t0 = OffsetDateTime.of(2026, 10, 2, 2, 0, 0, 0, ZoneOffset.UTC);
        Map<String, Object> publish = body(
                "patientId", patient, "planCode", plan, "versionNo", 1, "requestId", "H-PUB2",
                "items", List.of(body("fractionNo", 1, "deviceId", "DEVH2",
                        "complexityLevel", 1,
                        "windowStartUtc", t0, "windowEndUtc", t0.plusMinutes(10))));
        assertThat(post("/schedules", publish).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 同键同参重放
        ResponseEntity<Map> replay = post("/schedules", publish);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("replayed", true);

        // 同键异参：409
        Map<String, Object> conflict = body(
                "patientId", patient, "planCode", plan, "versionNo", 1, "requestId", "H-PUB2",
                "items", List.of(body("fractionNo", 1, "deviceId", "DEVH2",
                        "complexityLevel", 1,
                        "windowStartUtc", t0.plusMinutes(30),
                        "windowEndUtc", t0.plusMinutes(40))));
        ResponseEntity<Map> conflictResp = post("/schedules", conflict);
        assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflictResp.getBody()).containsEntry("code", "IDEMPOTENCY_FINGERPRINT_CONFLICT");

        // 参数非法：窗口长度不符 400
        Map<String, Object> badLength = body(
                "patientId", patient, "planCode", plan, "versionNo", 1, "requestId", "H-BADLEN",
                "items", List.of(body("fractionNo", 2, "deviceId", "DEVH2",
                        "complexityLevel", 2,
                        "windowStartUtc", t0.plusHours(2),
                        "windowEndUtc", t0.plusHours(2).plusMinutes(10))));
        ResponseEntity<Map> bad = post("/schedules", badLength);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 设备不存在：404
        Map<String, Object> noDevice = body(
                "patientId", patient, "planCode", plan, "versionNo", 1, "requestId", "H-NODEV",
                "items", List.of(body("fractionNo", 2, "deviceId", "GHOST",
                        "complexityLevel", 1,
                        "windowStartUtc", t0.plusHours(3),
                        "windowEndUtc", t0.plusHours(3).plusMinutes(10))));
        ResponseEntity<Map> notFound = post("/schedules", noDevice);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody()).containsEntry("code", "DEVICE_NOT_FOUND");
    }

    @Test
    @DisplayName("HTTP：取消排程释放占用；能力下调非下调422、版本不符409")
    void cancelAndCapabilityOverHttp() {
        String patient = "PH3";
        String plan = "PLANH3";
        verifiedPlanFixture(patient, plan, "TH3", "DEVH3");

        OffsetDateTime t0 = OffsetDateTime.of(2026, 10, 3, 2, 0, 0, 0, ZoneOffset.UTC);
        ResponseEntity<Map> published = post("/schedules", body(
                "patientId", patient, "planCode", plan, "versionNo", 1, "requestId", "H-PUB3",
                "items", List.of(body("fractionNo", 1, "deviceId", "DEVH3",
                        "complexityLevel", 2,
                        "windowStartUtc", t0, "windowEndUtc", t0.plusMinutes(20)))));
        Number scheduleId = (Number) ((Map<?, ?>) ((List<?>) published.getBody()
                .get("schedules")).get(0)).get("id");

        // 能力非下调（3→3）422
        ResponseEntity<Map> noDowngrade = post("/devices/capability", body(
                "deviceId", "DEVH3", "expectedVersion", 1, "newCapabilityLevel", 3,
                "requestId", "H-CAP-BAD"));
        assertThat(noDowngrade.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(noDowngrade.getBody()).containsEntry("code", "DEVICE_DOWNGRADE_ONLY");

        // expectedVersion 错误 409
        ResponseEntity<Map> staleVersion = post("/schedules/cancel", body(
                "scheduleId", scheduleId, "expectedVersion", 99, "requestId", "H-CAN-BAD"));
        assertThat(staleVersion.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(staleVersion.getBody()).containsEntry("code", "SCHEDULE_VERSION_CONFLICT");

        // 正确取消 200
        ResponseEntity<Map> cancel = post("/schedules/cancel", body(
                "scheduleId", scheduleId, "expectedVersion", 1, "requestId", "H-CAN"));
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancel.getBody()).containsEntry("status", "CANCELLED");
        assertThat(cancel.getBody()).containsEntry("version", 2);

        // 终态再取消 422
        ResponseEntity<Map> again = post("/schedules/cancel", body(
                "scheduleId", scheduleId, "expectedVersion", 2, "requestId", "H-CAN2"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 设备下调 3→2 成功，版本变为2
        ResponseEntity<Map> downgraded = post("/devices/capability", body(
                "deviceId", "DEVH3", "expectedVersion", 1, "newCapabilityLevel", 2,
                "requestId", "H-CAP"));
        assertThat(downgraded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downgraded.getBody()).containsEntry("version", 2);
        assertThat(downgraded.getBody()).containsEntry("capabilityLevel", 2);
    }
}
