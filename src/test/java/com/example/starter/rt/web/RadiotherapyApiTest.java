package com.example.starter.rt.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
 * 真实 HTTP 入口端到端测试：在随机端口启动完整容器，验证审核/批准/验证/分次/结案的
 * JSON 状态码、幂等重放与 409 指纹冲突在 REST 层的真实表现。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RadiotherapyApiTest {

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

    @Test
    @DisplayName("HTTP全链路：建档→建模板→建计划→审核→批准→验证(恰3%)→分次→结案")
    void fullChainOverHttp() {
        // 患者
        assertThat(post("/patients", body("patientId", "P9", "patientName", "HTTP患者"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 模板
        Map<String, Object> template = body(
                "templateCode", "T9",
                "templateName", "HTTP模板",
                "items", List.of(
                        body("organCode", "SPINAL_CORD", "organName", "脊髓",
                                "metricType", "DMAX_CGY", "metricRef", null,
                                "limitValue", new BigDecimal("4500.00")),
                        body("organCode", "BLADDER", "organName", "膀胱",
                                "metricType", "V_DOSE_PCT", "metricRef", 4000,
                                "limitValue", new BigDecimal("40.00"))));
        assertThat(post("/constraint-templates", template).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // 计划（恰等限值、勾稽一致）
        Map<String, Object> plan = body(
                "patientId", "P9", "planCode", "PLAN9", "templateCode", "T9",
                "prescribedTotalDoseCgy", 5000, "fractionDoseCgy", 200, "fractionCount", 25,
                "oarDoses", List.of(
                        body("organCode", "SPINAL_CORD", "metricType", "DMAX_CGY",
                                "metricRef", null, "metricValue", new BigDecimal("4500.00")),
                        body("organCode", "BLADDER", "metricType", "V_DOSE_PCT",
                                "metricRef", 4000, "metricValue", new BigDecimal("40.00"))));
        ResponseEntity<Map> created = post("/plans", plan);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("versionNo", 1);

        // 审核
        ResponseEntity<Map> audit = post("/audits",
                body("patientId", "P9", "planCode", "PLAN9", "versionNo", 1, "requestId", "H-AUD"));
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audit.getBody()).containsEntry("status", "REVIEWED");
        assertThat(audit.getBody()).containsEntry("passed", true);

        // 批准
        ResponseEntity<Map> approval = post("/approvals",
                body("patientId", "P9", "planCode", "PLAN9", "versionNo", 1, "requestId", "H-APV"));
        assertThat(approval.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approval.getBody()).containsEntry("status", "APPROVED");

        // 验证 恰 3%
        ResponseEntity<Map> verify = post("/verifications",
                body("patientId", "P9", "planCode", "PLAN9", "versionNo", 1,
                        "plannedDoseCgy", 200, "measuredDoseCgy", 206, "requestId", "H-VER"));
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).containsEntry("passed", true);
        assertThat(((Number) verify.getBody().get("deviationPct")).doubleValue())
                .isCloseTo(3.0, org.assertj.core.data.Offset.offset(0.0001));

        // 分次
        ResponseEntity<Map> fraction = post("/fractions",
                body("patientId", "P9", "planCode", "PLAN9", "fractionNo", 1,
                        "deliveredDoseCgy", 200, "requestId", "H-FR"));
        assertThat(fraction.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fraction.getBody()).containsEntry("fractionNo", 1);
        assertThat(fraction.getBody()).containsEntry("replayed", false);

        // 结案
        ResponseEntity<Map> close = post("/closures",
                body("patientId", "P9", "planCode", "PLAN9", "requestId", "H-CLS"));
        assertThat(close.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(close.getBody()).containsEntry("status", "CLOSED");
    }

    @Test
    @DisplayName("HTTP：同键重放返回200且replayed=true；同键异参返回409")
    void replayAndFingerprintConflictOverHttp() {
        post("/patients", body("patientId", "P8", "patientName", "幂等患者"));
        Map<String, Object> template = body(
                "templateCode", "T8", "templateName", "T8",
                "items", List.of(body("organCode", "SPINAL_CORD", "organName", "脊髓",
                        "metricType", "DMAX_CGY", "metricRef", null,
                        "limitValue", new BigDecimal("4500.00"))));
        post("/constraint-templates", template);
        post("/plans", body(
                "patientId", "P8", "planCode", "PLAN8", "templateCode", "T8",
                "prescribedTotalDoseCgy", 5000, "fractionDoseCgy", 200, "fractionCount", 25,
                "oarDoses", List.of(body("organCode", "SPINAL_CORD", "metricType", "DMAX_CGY",
                        "metricRef", null, "metricValue", new BigDecimal("4500.00")))));
        post("/audits", body("patientId", "P8", "planCode", "PLAN8", "versionNo", 1,
                "requestId", "I-AUD"));
        post("/approvals", body("patientId", "P8", "planCode", "PLAN8", "versionNo", 1,
                "requestId", "I-APV"));
        post("/verifications", body("patientId", "P8", "planCode", "PLAN8", "versionNo", 1,
                "plannedDoseCgy", 200, "measuredDoseCgy", 200, "requestId", "I-VER"));

        Map<String, Object> frac = body("patientId", "P8", "planCode", "PLAN8", "fractionNo", 1,
                "deliveredDoseCgy", 200, "requestId", "I-FR");
        assertThat(post("/fractions", frac).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 同键同参重放
        ResponseEntity<Map> replay = post("/fractions", frac);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("replayed", true);

        // 同键异参：409
        Map<String, Object> conflictParams = body("patientId", "P8", "planCode", "PLAN8",
                "fractionNo", 2, "deliveredDoseCgy", 200, "requestId", "I-FR");
        ResponseEntity<Map> conflict = post("/fractions", conflictParams);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).containsEntry("code", "IDEMPOTENCY_FINGERPRINT_CONFLICT");
    }

    @Test
    @DisplayName("HTTP：跳次返回409且响应体alert=true")
    void skipFractionAlertOverHttp() {
        post("/patients", body("patientId", "P7", "patientName", "跳次患者"));
        Map<String, Object> template = body(
                "templateCode", "T7", "templateName", "T7",
                "items", List.of(body("organCode", "SPINAL_CORD", "organName", "脊髓",
                        "metricType", "DMAX_CGY", "metricRef", null,
                        "limitValue", new BigDecimal("4500.00"))));
        post("/constraint-templates", template);
        post("/plans", body(
                "patientId", "P7", "planCode", "PLAN7", "templateCode", "T7",
                "prescribedTotalDoseCgy", 5000, "fractionDoseCgy", 200, "fractionCount", 25,
                "oarDoses", List.of(body("organCode", "SPINAL_CORD", "metricType", "DMAX_CGY",
                        "metricRef", null, "metricValue", new BigDecimal("4500.00")))));
        post("/audits", body("patientId", "P7", "planCode", "PLAN7", "versionNo", 1,
                "requestId", "S-AUD"));
        post("/approvals", body("patientId", "P7", "planCode", "PLAN7", "versionNo", 1,
                "requestId", "S-APV"));
        post("/verifications", body("patientId", "P7", "planCode", "PLAN7", "versionNo", 1,
                "plannedDoseCgy", 200, "measuredDoseCgy", 200, "requestId", "S-VER"));

        ResponseEntity<Map> skip = post("/fractions",
                body("patientId", "P7", "planCode", "PLAN7", "fractionNo", 3,
                        "deliveredDoseCgy", 200, "requestId", "S-FR"));
        assertThat(skip.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(skip.getBody()).containsEntry("code", "FRACTION_SKIP_DETECTED");
        assertThat(skip.getBody()).containsEntry("alert", true);
    }
}
