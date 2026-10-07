package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输片段核验真实 HTTP 入口测试：
 * 端到端跑通 登记→绑定→两片段完整证据→签发→台账查询；
 * 漏一个端点经 REST 拒签；参数校验 400、未知批号 404。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ColdChainApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-31T00:00:00Z");
    private static final Instant TRANSPORT_START = Instant.parse("2026-08-31T10:00:00Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-31T10:30:00Z");

    private String coldchainUrl(String path) {
        return "http://localhost:" + port + "/api/coldchain" + path;
    }

    private String customsUrl(String path) {
        return "http://localhost:" + port + "/api/customs" + path;
    }

    private String releaseUrl(String path) {
        return "http://localhost:" + port + "/api/batch-releases" + path;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map> post(String url, Object body) {
        return restTemplate.postForEntity(url, body, Map.class);
    }

    private static List<Map<String, Object>> readings(Instant start, Instant end) {
        List<Map<String, Object>> readings = new java.util.ArrayList<>();
        int seq = 1;
        for (Instant at = start; !at.isAfter(end); at = at.plusSeconds(300)) {
            readings.add(Map.of("seq", seq++, "at", at.toString(), "tempCelsius", 5.0));
        }
        return readings;
    }

    private static List<Map<String, Object>> twoSegmentsBody() {
        Instant mid = TRANSPORT_START.plusSeconds(600);
        Instant end = TRANSPORT_START.plusSeconds(1200);
        return List.of(
                Map.of("seq", 1, "start", TRANSPORT_START.toString(), "end", mid.toString(),
                        "readings", readings(TRANSPORT_START, mid)),
                Map.of("seq", 2, "start", mid.toString(), "end", end.toString(),
                        "readings", readings(mid, end)));
    }

    /** 经 HTTP/service 准备一个已绑定通关单、公示期满生效的进口子批。 */
    private void prepareBoundBatch(String suffix) {
        clock.setFixed(T0);
        post(customsUrl("/declarations"), Map.of(
                "requestId", "ccapi-reg-" + suffix,
                "customsNo", "CI-CCAPI-" + suffix,
                "enterpriseName", IMPORT_ENTERPRISE,
                "productName", IMPORT_PRODUCT,
                "validUntil", VALID_UNTIL.toString(),
                "totalQuantity", 100,
                "at", T0.toString()));
        prepareIssueableImportBatch("ccapi-d-" + suffix, "B-CCAPI-" + suffix);
        clock.setFixed(ISSUE_AT);
        restTemplate.postForEntity(releaseUrl("/B-CCAPI-" + suffix + "/publicity-settlement"),
                null, Map.class);
        post(customsUrl("/declarations/CI-CCAPI-" + suffix + "/bindings"), Map.of(
                "requestId", "ccapi-bind-" + suffix,
                "customsNo", "CI-CCAPI-" + suffix,
                "batchNo", "B-CCAPI-" + suffix,
                "declaredQuantity", 30,
                "expectedVersion", 1,
                "at", T0.toString()));
    }

    @Test
    void twoSegmentsCompleteThenIssueOverHttp() {
        prepareBoundBatch("ok");
        ResponseEntity<Map> submitted = post(coldchainUrl("/subbatches/B-CCAPI-ok/transports"),
                Map.of("requestId", "ccapi-sub-ok",
                        "expectedVersion", 0,
                        "submittedAt", SUBMITTED_AT.toString(),
                        "segments", twoSegmentsBody()));
        assertEquals(HttpStatus.OK, submitted.getStatusCode());
        assertEquals("QUALIFIED", submitted.getBody().get("status"));
        assertEquals(1, submitted.getBody().get("version"));
        assertEquals(Boolean.TRUE, submitted.getBody().get("certValid"));

        ResponseEntity<Map> issued = post(releaseUrl("/B-CCAPI-ok/issues"),
                Map.of("requestId", "ccapi-issue-ok"));
        assertEquals(HttpStatus.OK, issued.getStatusCode(), "两片段完整合格后应签发成功");

        ResponseEntity<Map> ledger = restTemplate.getForEntity(
                coldchainUrl("/subbatches/B-CCAPI-ok/transports"), Map.class);
        assertEquals(HttpStatus.OK, ledger.getStatusCode());
        assertEquals("QUALIFIED", ledger.getBody().get("status"));
        assertEquals(1, ledger.getBody().get("certVersion"));
        List<?> segments = (List<?>) ledger.getBody().get("segments");
        assertEquals(2, segments.size());
    }

    @Test
    void missingOneEndSampleRejectsIssueOverHttp() {
        prepareBoundBatch("gap");
        // 第二片缺终点样本。
        List<Map<String, Object>> segments = new java.util.ArrayList<>(twoSegmentsBody());
        Map<String, Object> second = new java.util.HashMap<>(segments.get(1));
        List<Map<String, Object>> secondReadings = new java.util.ArrayList<>(readings(
                TRANSPORT_START.plusSeconds(600), TRANSPORT_START.plusSeconds(1200)));
        secondReadings.remove(secondReadings.size() - 1);
        second.put("readings", secondReadings);
        segments.set(1, second);

        ResponseEntity<Map> submitted = post(coldchainUrl("/subbatches/B-CCAPI-gap/transports"),
                Map.of("requestId", "ccapi-sub-gap",
                        "expectedVersion", 0,
                        "submittedAt", SUBMITTED_AT.toString(),
                        "segments", segments));
        assertEquals(HttpStatus.OK, submitted.getStatusCode());
        assertEquals("UNQUALIFIED", submitted.getBody().get("status"));
        List<?> violations = (List<?>) submitted.getBody().get("violations");
        assertEquals(1, violations.size());
        assertEquals("MISSING_END_SAMPLE", ((Map<?, ?>) violations.get(0)).get("code"));

        ResponseEntity<Map> issued = post(releaseUrl("/B-CCAPI-gap/issues"),
                Map.of("requestId", "ccapi-issue-gap"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, issued.getStatusCode(), "漏一个端点必须拒签");
        assertEquals("COLDCHAIN_CERT_NOT_QUALIFIED", issued.getBody().get("error"));
    }

    @Test
    void validationAndUnknownBatchOverHttp() {
        // 0 个片段：400 参数校验。
        ResponseEntity<Map> empty = post(coldchainUrl("/subbatches/B-CCAPI-x/transports"),
                Map.of("requestId", "ccapi-sub-empty",
                        "expectedVersion", 0,
                        "submittedAt", SUBMITTED_AT.toString(),
                        "segments", List.of()));
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
        assertEquals("VALIDATION_FAILED", empty.getBody().get("error"));

        // 未知批号提交：404。
        ResponseEntity<Map> unknown = post(coldchainUrl("/subbatches/B-CCAPI-none/transports"),
                Map.of("requestId", "ccapi-sub-none",
                        "expectedVersion", 0,
                        "submittedAt", SUBMITTED_AT.toString(),
                        "segments", twoSegmentsBody()));
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode());
        assertEquals("REVIEW_NOT_FOUND", unknown.getBody().get("error"));

        // 未启用冷链的批号查询台账：404。
        clock.setFixed(T0);
        prepareIssueableImportBatch("ccapi-d-off", "B-CCAPI-off");
        ResponseEntity<Map> notEnabled = restTemplate.getForEntity(
                coldchainUrl("/subbatches/B-CCAPI-off/transports"), Map.class);
        assertEquals(HttpStatus.NOT_FOUND, notEnabled.getStatusCode());
        assertEquals("COLDCHAIN_NOT_ENABLED", notEnabled.getBody().get("error"));
        assertTrue(true);
    }
}
