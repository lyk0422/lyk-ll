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
 * 冷链片段核验真实 HTTP 入口测试：两片段完整证据合格后签发、
 * 漏一个端点拒核且拒签、缺序号 400、查询展示缺口与冻结版本。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ColdChainApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant ISSUE_AT = Instant.parse("2026-09-08T00:00:00Z");
    private static final Instant SEG_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant SEG_MID = Instant.parse("2026-09-01T00:10:00Z");
    private static final Instant SEG_END = Instant.parse("2026-09-01T00:20:00Z");
    private static final Instant SUBMIT_AT = Instant.parse("2026-09-01T03:00:00Z");

    private String coldChainUrl(String path) {
        return "http://localhost:" + port + "/api/coldchain/subbatches" + path;
    }

    private String releaseUrl(String path) {
        return "http://localhost:" + port + "/api/batch-releases" + path;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, Object> reading(int seq, String at, String temperature) {
        return Map.of("seq", seq, "at", at, "temperature", temperature);
    }

    private static List<Map<String, Object>> twoSegmentsBody() {
        return List.of(
                Map.of("seq", 1, "startUtc", SEG_START.toString(), "endUtc", SEG_MID.toString()),
                Map.of("seq", 2, "startUtc", SEG_MID.toString(), "endUtc", SEG_END.toString()));
    }

    private static List<Map<String, Object>> qualifiedReadingsBody() {
        return List.of(
                Map.of("segmentSeq", 1, "readings", List.of(
                        reading(1, "2026-09-01T00:00:00Z", "2.0"),
                        reading(2, "2026-09-01T00:05:00Z", "5.0"),
                        reading(3, "2026-09-01T00:10:00Z", "8.0"))),
                Map.of("segmentSeq", 2, "readings", List.of(
                        reading(1, "2026-09-01T00:10:00Z", "4.0"),
                        reading(2, "2026-09-01T00:15:00Z", "6.0"),
                        reading(3, "2026-09-01T00:20:00Z", "2.0"))));
    }

    /** 申报 → 评定 → 公示期满生效（HTTP 入口）。 */
    @SuppressWarnings("rawtypes")
    private void makeIssueableOverHttp(String batchNo) {
        clock.setFixed(T0);
        restTemplate.postForEntity(releaseUrl("/declarations"), Map.of(
                "requestId", "api-cc-d-" + batchNo,
                "enterpriseName", IMPORT_ENTERPRISE,
                "productName", IMPORT_PRODUCT,
                "batchNo", batchNo,
                "documents", allDocuments(),
                "inspections", List.of(
                        Map.of("itemCode", "POTENCY", "measuredValue", "4.0"),
                        Map.of("itemCode", "STERILITY", "measuredValue", "无菌"),
                        Map.of("itemCode", "ABNORMAL_TOXICITY", "measuredValue", "0.10"),
                        Map.of("itemCode", "PH_VALUE", "measuredValue", "7.5"),
                        Map.of("itemCode", "MOISTURE", "measuredValue", "2.0"))), Map.class);
        restTemplate.postForEntity(releaseUrl("/" + batchNo + "/evaluation"), null, Map.class);
        clock.setFixed(ISSUE_AT);
        restTemplate.postForEntity(releaseUrl("/" + batchNo + "/publicity-settlement"), null, Map.class);
    }

    @SuppressWarnings("rawtypes")
    @Test
    void twoSegmentsCompleteEvidenceQualifiesAndIssuesOverHttp() {
        makeIssueableOverHttp("B-API-CC-OK");

        // 登记两个相接片段：版本 1，冻结运输起止。
        ResponseEntity<Map> registered = restTemplate.postForEntity(
                coldChainUrl("/B-API-CC-OK/segments"), Map.of(
                        "requestId", "api-cc-reg-ok",
                        "batchNo", "B-API-CC-OK",
                        "segments", twoSegmentsBody(),
                        "at", SUBMIT_AT.toString()), Map.class);
        assertEquals(HttpStatus.OK, registered.getStatusCode());
        assertEquals(1, registered.getBody().get("version"));
        assertEquals(Boolean.FALSE, registered.getBody().get("certificateValid"));
        assertEquals(SEG_START.toString(), registered.getBody().get("transportStartUtc"));
        assertEquals(SEG_END.toString(), registered.getBody().get("transportEndUtc"));

        // 提交两片段完整读数（恰 2/8 度边界、恰 5 分钟间隔）：合格冻结版本。
        ResponseEntity<Map> submitted = restTemplate.postForEntity(
                coldChainUrl("/B-API-CC-OK/readings"), Map.of(
                        "requestId", "api-cc-sub-ok",
                        "batchNo", "B-API-CC-OK",
                        "expectedVersion", 1,
                        "segments", qualifiedReadingsBody(),
                        "at", SUBMIT_AT.toString()), Map.class);
        assertEquals(HttpStatus.OK, submitted.getStatusCode());
        assertEquals(Boolean.TRUE, submitted.getBody().get("certificateValid"));
        assertEquals(1, submitted.getBody().get("qualifiedVersion"));

        // 原签发入口：温证合格放行。
        clock.setFixed(ISSUE_AT);
        ResponseEntity<Map> issued = restTemplate.postForEntity(
                releaseUrl("/B-API-CC-OK/issues"), Map.of("requestId", "api-cc-issue-ok"), Map.class);
        assertEquals(HttpStatus.OK, issued.getStatusCode());
        assertEquals("ISSUED", issued.getBody().get("status"));
    }

    @SuppressWarnings("rawtypes")
    @Test
    void missingOneEndpointRejectsQualificationAndBlocksIssueOverHttp() {
        makeIssueableOverHttp("B-API-CC-GAP");
        restTemplate.postForEntity(coldChainUrl("/B-API-CC-GAP/segments"), Map.of(
                "requestId", "api-cc-reg-gap",
                "batchNo", "B-API-CC-GAP",
                "segments", twoSegmentsBody(),
                "at", SUBMIT_AT.toString()), Map.class);

        // 片段 2 漏终点样本：422 拒核。
        List<Map<String, Object>> missingEnd = List.of(
                qualifiedReadingsBody().get(0),
                Map.of("segmentSeq", 2, "readings", List.of(
                        reading(1, "2026-09-01T00:10:00Z", "4.0"),
                        reading(2, "2026-09-01T00:15:00Z", "6.0"))));
        ResponseEntity<Map> rejected = restTemplate.postForEntity(
                coldChainUrl("/B-API-CC-GAP/readings"), Map.of(
                        "requestId", "api-cc-sub-gap",
                        "batchNo", "B-API-CC-GAP",
                        "expectedVersion", 1,
                        "segments", missingEnd,
                        "at", SUBMIT_AT.toString()), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatusCode());
        assertEquals("COLD_CHAIN_EVIDENCE_INCOMPLETE", rejected.getBody().get("error"));

        // 查询展示缺口与未合格状态。
        ResponseEntity<Map> view = restTemplate.getForEntity(
                coldChainUrl("/B-API-CC-GAP"), Map.class);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertEquals(Boolean.FALSE, view.getBody().get("certificateValid"));
        assertTrue(view.getBody().get("gaps").toString().contains("缺少终点"),
                "查询须展示缺端点缺口: " + view.getBody().get("gaps"));

        // 签发被温证门禁阻断。
        clock.setFixed(ISSUE_AT);
        ResponseEntity<Map> blocked = restTemplate.postForEntity(
                releaseUrl("/B-API-CC-GAP/issues"), Map.of("requestId", "api-cc-issue-gap"), Map.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, blocked.getStatusCode());
        assertEquals("COLD_CHAIN_CERT_INVALID", blocked.getBody().get("error"));
    }

    @SuppressWarnings("rawtypes")
    @Test
    void missingReadingSeqReturns400OverHttp() {
        makeIssueableOverHttp("B-API-CC-SEQ");
        restTemplate.postForEntity(coldChainUrl("/B-API-CC-SEQ/segments"), Map.of(
                "requestId", "api-cc-reg-seq",
                "batchNo", "B-API-CC-SEQ",
                "segments", twoSegmentsBody(),
                "at", SUBMIT_AT.toString()), Map.class);

        // 缺序号读数：Bean 校验 400，不得通过。
        java.util.Map<String, Object> noSeq = new java.util.HashMap<>();
        noSeq.put("at", "2026-09-01T00:00:00Z");
        noSeq.put("temperature", "4.0");
        ResponseEntity<Map> rejected = restTemplate.postForEntity(
                coldChainUrl("/B-API-CC-SEQ/readings"), Map.of(
                        "requestId", "api-cc-sub-seq",
                        "batchNo", "B-API-CC-SEQ",
                        "expectedVersion", 1,
                        "segments", List.of(
                                Map.of("segmentSeq", 1, "readings", List.of(noSeq,
                                        reading(2, "2026-09-01T00:05:00Z", "5.0"),
                                        reading(3, "2026-09-01T00:10:00Z", "6.0"))),
                                qualifiedReadingsBody().get(1)),
                        "at", SUBMIT_AT.toString()), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertEquals("VALIDATION_FAILED", rejected.getBody().get("error"));
    }
}
