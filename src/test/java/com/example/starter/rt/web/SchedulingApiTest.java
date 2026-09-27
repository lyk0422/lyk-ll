package com.example.starter.rt.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * 排程门禁 REST 端到端测试：真实 HTTP 验证状态码、Bean 校验 400、404 与幂等 409。
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

    private Map<String, Object> windowItem(int fractionNo, OffsetDateTime start, int minutes) {
        DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        return body("fractionNo", fractionNo,
                "windowStartUtc", fmt.format(start),
                "windowEndUtc", fmt.format(start.plusMinutes(minutes)));
    }

    /** 通过 HTTP 准备一名已验证计划的患者。 */
    private void setupVerifiedPlanOverHttp() {
        assertThat(post("/patients", body("patientId", "PH1", "patientName", "HTTP排程患者"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> template = body(
                "templateCode", "TH1", "templateName", "HTTP模板",
                "items", List.of(
                        body("organCode", "SPINAL_CORD", "organName", "脊髓",
                                "metricType", "DMAX_CGY", "metricRef", null,
                                "limitValue", new BigDecimal("4500.00")),
                        body("organCode", "BLADDER", "organName", "膀胱",
                                "metricType", "V_DOSE_PCT", "metricRef", 4000,
                                "limitValue", new BigDecimal("40.00"))));
        assertThat(post("/constraint-templates", template).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> plan = body(
                "patientId", "PH1", "planCode", "PLANH1", "templateCode", "TH1",
                "prescribedTotalDoseCgy", 5000, "fractionDoseCgy", 200, "fractionCount", 25,
                "oarDoses", List.of(
                        body("organCode", "SPINAL_CORD", "metricType", "DMAX_CGY",
                                "metricRef", null, "metricValue", new BigDecimal("4400.00")),
                        body("organCode", "BLADDER", "metricType", "V_DOSE_PCT",
                                "metricRef", 4000, "metricValue", new BigDecimal("35.00"))));
        assertThat(post("/plans", plan).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/audits", body("patientId", "PH1", "planCode", "PLANH1",
                "versionNo", 1, "requestId", "SH-AUD")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/approvals", body("patientId", "PH1", "planCode", "PLANH1",
                "versionNo", 1, "requestId", "SH-APV")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/verifications", body("patientId", "PH1", "planCode", "PLANH1",
                "versionNo", 1, "plannedDoseCgy", 200, "measuredDoseCgy", 200,
                "requestId", "SH-VER")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("HTTP全链路：登记设备→发布排程→占用查询→窗口内治疗→占用释放")
    void schedulingChainOverHttp() {
        setupVerifiedPlanOverHttp();

        // 登记设备
        ResponseEntity<Map> device = post("/devices",
                body("deviceId", "DEVH1", "deviceName", "HTTP合成设备", "maxLevel", 3,
                        "requestId", "SH-DEV"));
        assertThat(device.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(device.getBody()).containsEntry("version", 1);

        // 发布排程：窗口覆盖当前时刻（前5分钟起10分钟，等级1）
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        ResponseEntity<Map> published = post("/schedules",
                body("patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DEVH1",
                        "items", List.of(windowItem(1, start, 10)), "requestId", "SH-PUB"));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(published.getBody()).containsEntry("status", "ACTIVE");
        assertThat(published.getBody()).containsEntry("version", 1);
        Number scheduleId = (Number) published.getBody().get("scheduleId");

        // 占用查询：1 项
        ResponseEntity<List> occupancy = rest.getForEntity(
                url("/devices/DEVH1/occupancy"), List.class);
        assertThat(occupancy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(occupancy.getBody()).hasSize(1);

        // 窗口内受控治疗
        ResponseEntity<Map> treated = post("/treatments",
                body("patientId", "PH1", "planCode", "PLANH1", "fractionNo", 1,
                        "deliveredDoseCgy", 200, "requestId", "SH-TRT"));
        assertThat(treated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(treated.getBody()).containsEntry("fractionNo", 1);

        // 完成后占用释放
        ResponseEntity<List> after = rest.getForEntity(url("/devices/DEVH1/occupancy"), List.class);
        assertThat(after.getBody()).isEmpty();

        // 排程取消（已完成的批次可取消，版本+1）
        ResponseEntity<Map> cancelled = post("/schedules/" + scheduleId + "/cancellation",
                body("expectedVersion", 1, "requestId", "SH-CXL"));
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELLED");
        assertThat(cancelled.getBody()).containsEntry("version", 2);
    }

    @Test
    @DisplayName("HTTP参数校验：窗口长度不符400；等级越界400；设备不存在404；同键异参409")
    void schedulingValidationOverHttp() {
        setupVerifiedPlanOverHttp();
        post("/devices", body("deviceId", "DEVH2", "deviceName", "HTTP合成设备2", "maxLevel", 1,
                "requestId", "SH-DEV2"));

        // 窗口长度 15 分钟 ≠ 等级1 的 10 分钟 → 400
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC);
        ResponseEntity<Map> badLength = post("/schedules",
                body("patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DEVH2",
                        "items", List.of(windowItem(1, start, 15)), "requestId", "SH-BAD1"));
        assertThat(badLength.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badLength.getBody()).containsEntry("code", "VALIDATION_FAILED");

        // 等级 5 越界 → 400（Bean 校验）
        ResponseEntity<Map> badLevel = post("/schedules",
                body("patientId", "PH1", "planCode", "PLANH1", "level", 5, "deviceId", "DEVH2",
                        "items", List.of(windowItem(1, start, 10)), "requestId", "SH-BAD2"));
        assertThat(badLevel.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 设备不存在 → 404
        ResponseEntity<Map> noDevice = post("/schedules",
                body("patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DEV_NONE",
                        "items", List.of(windowItem(1, start, 10)), "requestId", "SH-BAD3"));
        assertThat(noDevice.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> noDeviceOccupancy = rest.getForEntity(
                url("/devices/DEV_NONE/occupancy"), Map.class);
        assertThat(noDeviceOccupancy.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 排程不存在 → 404
        ResponseEntity<Map> noSchedule = post("/schedules/99999/cancellation",
                body("expectedVersion", 1, "requestId", "SH-BAD4"));
        assertThat(noSchedule.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 成功发布后用同 requestId 异参 → 409 指纹冲突
        OffsetDateTime s1 = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
        assertThat(post("/schedules",
                body("patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DEVH2",
                        "items", List.of(windowItem(1, s1, 10)), "requestId", "SH-IDEM"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> conflict = post("/schedules",
                body("patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DEVH2",
                        "items", List.of(windowItem(1, s1.plusHours(3), 10)),
                        "requestId", "SH-IDEM"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).containsEntry("code", "IDEMPOTENCY_FINGERPRINT_CONFLICT");

        // 同键同参 → 重放首次结果
        ResponseEntity<Map> replay = post("/schedules",
                body("patientId", "PH1", "planCode", "PLANH1", "level", 1, "deviceId", "DEVH2",
                        "items", List.of(windowItem(1, s1, 10)), "requestId", "SH-IDEM"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).containsEntry("replayed", true);
    }
}
