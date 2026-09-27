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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.starter.rt.repository.PlanRepository;

/**
 * 排程相关 REST 入口端到端测试：设备登记/能力变更、排程发布/取消、受控治疗与占用查询
 * 在真实 HTTP 层的状态码与错误语义（400/404/409/422）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleApiTest {

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

    private ResponseEntity<Map> put(String path, Map<String, Object> body) {
        return rest.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body), Map.class);
    }

    /** 推进到已批准且首次验证合格的当前计划。 */
    private void verifiedPlan(String patientId, String planCode) {
        assertThat(post("/patients", body("patientId", patientId, "patientName", "排程HTTP患者"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> template = body(
                "templateCode", "TPL_H_" + patientId,
                "templateName", "排程HTTP模板",
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
                "patientId", patientId, "planCode", planCode, "templateCode", "TPL_H_" + patientId,
                "prescribedTotalDoseCgy", 1000, "fractionDoseCgy", 200, "fractionCount", 5,
                "oarDoses", List.of(
                        body("organCode", "SPINAL_CORD", "metricType", "DMAX_CGY",
                                "metricRef", null, "metricValue", new BigDecimal("4400.00")),
                        body("organCode", "BLADDER", "metricType", "V_DOSE_PCT",
                                "metricRef", 4000, "metricValue", new BigDecimal("35.00"))));
        assertThat(post("/plans", plan).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post("/audits", body("patientId", patientId, "planCode", planCode,
                "versionNo", 1, "requestId", "H-AUD-" + patientId)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/approvals", body("patientId", patientId, "planCode", planCode,
                "versionNo", 1, "requestId", "H-APV-" + patientId)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/verifications", body("patientId", patientId, "planCode", planCode,
                "versionNo", 1, "plannedDoseCgy", 200, "measuredDoseCgy", 200,
                "requestId", "H-VER-" + patientId)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> scheduleBody(String patientId, String planCode, String deviceId,
                                             String requestId, int fractionNo, Instant start) {
        return body("patientId", patientId, "planCode", planCode, "versionNo", 1,
                "deviceId", deviceId, "level", 1,
                "items", List.of(body("fractionNo", fractionNo,
                        "startAt", start.toString(),
                        "endAt", start.plus(10, ChronoUnit.MINUTES).toString())),
                "requestId", requestId);
    }

    @Test
    @DisplayName("HTTP排程全链路：建设备→发布→治疗→占用查询→取消")
    void scheduleChainOverHttp() {
        verifiedPlan("PH1", "PLAN_H1");

        // 登记设备
        ResponseEntity<Map> device = post("/devices",
                body("deviceId", "DEV_H1", "deviceName", "HTTP合成加速器", "maxLevel", 3));
        assertThat(device.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(device.getBody()).containsEntry("version", 1);

        // 发布排程（窗口覆盖当前时刻）
        Instant start = Instant.now().minus(5, ChronoUnit.MINUTES)
                .truncatedTo(ChronoUnit.SECONDS);
        ResponseEntity<Map> published = post("/schedules",
                scheduleBody("PH1", "PLAN_H1", "DEV_H1", "H-SCH-1", 1, start));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody()).containsEntry("status", "ACTIVE");
        assertThat(published.getBody()).containsEntry("requiredMinutes", 10);
        Number batchId = (Number) published.getBody().get("batchId");

        // 占用查询：1 条 ACTIVE
        ResponseEntity<List> occupancy = rest.getForEntity(
                url("/devices/DEV_H1/occupancy"), List.class);
        assertThat(occupancy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(occupancy.getBody()).hasSize(1);

        // 受控治疗：窗口内执行成功
        ResponseEntity<Map> treated = post("/treatments",
                body("patientId", "PH1", "planCode", "PLAN_H1", "fractionNo", 1,
                        "deliveredDoseCgy", 200, "requestId", "H-TRT-1"));
        assertThat(treated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(treated.getBody()).containsEntry("fractionNo", 1);

        // 占用已释放（COMPLETED）
        ResponseEntity<List> after = rest.getForEntity(url("/devices/DEV_H1/occupancy"), List.class);
        assertThat(((Map<?, ?>) after.getBody().get(0)).get("status")).isEqualTo("COMPLETED");

        // 取消已完成批次：422
        ResponseEntity<Map> cancelLate = post("/schedules/" + batchId + "/cancel",
                body("expectedVersion", 2, "requestId", "H-CXL-1"));
        assertThat(cancelLate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("HTTP取消链路：发布后取消释放占用，版本加1")
    void cancelOverHttp() {
        verifiedPlan("PH2", "PLAN_H2");
        assertThat(post("/devices", body("deviceId", "DEV_H2", "deviceName", "HTTP加速器2",
                "maxLevel", 2)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        ResponseEntity<Map> published = post("/schedules",
                scheduleBody("PH2", "PLAN_H2", "DEV_H2", "H-SCH-2", 1, start));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        Number batchId = (Number) published.getBody().get("batchId");

        ResponseEntity<Map> cancelled = post("/schedules/" + batchId + "/cancel",
                body("expectedVersion", 1, "requestId", "H-CXL-2"));
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELLED");
        assertThat(cancelled.getBody()).containsEntry("batchVersion", 2);
    }

    @Test
    @DisplayName("HTTP错误语义：窗口长度不符400、设备不存在404、指纹冲突409、窗口外治疗422")
    void errorSemanticsOverHttp() {
        verifiedPlan("PH3", "PLAN_H3");
        assertThat(post("/devices", body("deviceId", "DEV_H3", "deviceName", "HTTP加速器3",
                "maxLevel", 3)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        // 400：窗口长度 15 分钟 ≠ 等级1的10分钟
        ResponseEntity<Map> badWindow = post("/schedules",
                body("patientId", "PH3", "planCode", "PLAN_H3", "versionNo", 1,
                        "deviceId", "DEV_H3", "level", 1,
                        "items", List.of(body("fractionNo", 1, "startAt", start.toString(),
                                "endAt", start.plus(15, ChronoUnit.MINUTES).toString())),
                        "requestId", "H-BAD-1"));
        assertThat(badWindow.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 404：设备不存在
        ResponseEntity<Map> noDevice = put("/devices/DEV_NONE/capability",
                body("maxLevel", 2, "expectedVersion", 1, "requestId", "H-CAP-404"));
        assertThat(noDevice.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 409：同 requestId 不同参数
        assertThat(post("/schedules", scheduleBody("PH3", "PLAN_H3", "DEV_H3", "H-SCH-3", 1, start))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> conflict = post("/schedules",
                scheduleBody("PH3", "PLAN_H3", "DEV_H3", "H-SCH-3", 2,
                        start.plus(30, ChronoUnit.MINUTES)));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 422：窗口外治疗（窗口在未来）
        ResponseEntity<Map> outside = post("/treatments",
                body("patientId", "PH3", "planCode", "PLAN_H3", "fractionNo", 1,
                        "deliveredDoseCgy", 200, "requestId", "H-TRT-3"));
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
