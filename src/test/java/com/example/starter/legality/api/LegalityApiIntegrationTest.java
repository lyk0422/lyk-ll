package com.example.starter.legality.api;

import com.example.starter.legality.AbstractH2IntegrationTest;
import com.example.starter.legality.api.dto.CheckRequest;
import com.example.starter.legality.api.dto.CheckResponse;
import com.example.starter.legality.api.dto.DelayRequest;
import com.example.starter.legality.api.dto.DelayResponse;
import com.example.starter.legality.api.dto.MemberDutyRequest;
import com.example.starter.legality.api.dto.SealResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机组值勤合规 API 端到端测试：走真实 HTTP 入口，校验状态码与完整业务流程
 * （报到校验 → 延误延长 → 封板；含 409 幂等冲突与 404）。
 */
@org.springframework.boot.test.context.SpringBootTest(webEnvironment =
        org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:crew_legality_web;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=0")
class LegalityApiIntegrationTest extends AbstractH2IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void fullFlow_reportCheck_delayExtension_seal() {
        // 1) 报到 + 整组校验：计划 FDP 10 小时，06:00（UTC）档上限 13 小时，合法。
        MemberDutyRequest duty = new MemberDutyRequest(
                "api-req-1", "C001", "UT001",
                Instant.parse("2026-11-01T06:00:00Z"),
                Instant.parse("2026-11-01T16:00:00Z"));
        ResponseEntity<CheckResponse> checkResp = restTemplate.postForEntity(
                url("/api/legality/checks"), new CheckRequest(List.of(duty)), CheckResponse.class);
        assertEquals(HttpStatus.CREATED, checkResp.getStatusCode());
        CheckResponse body = checkResp.getBody();
        assertEquals("LEGAL", body.overallResult());
        assertFalse(body.sealed());
        String batchId = body.batchId();

        // 2) 同键异参：409，且不写入新记录。
        MemberDutyRequest changed = new MemberDutyRequest(
                "api-req-1", "C001", "UT001",
                Instant.parse("2026-11-01T06:00:00Z"),
                Instant.parse("2026-11-01T17:00:00Z"));
        ResponseEntity<Map> conflict = restTemplate.postForEntity(
                url("/api/legality/checks"), new CheckRequest(List.of(changed)), Map.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("CONFLICT", conflict.getBody().get("error"));

        // 3) 延误延长 120 分钟（恰满每日 2 小时）：FDP 变 12 小时，仍合法。
        DelayRequest delay = new DelayRequest("api-delay-1", "api-req-1", 120, "合成天气延误");
        ResponseEntity<DelayResponse> delayResp = restTemplate.postForEntity(
                url("/api/legality/delays"), delay, DelayResponse.class);
        assertEquals(HttpStatus.CREATED, delayResp.getStatusCode());
        DelayResponse delayBody = delayResp.getBody();
        assertTrue(delayBody.accepted());
        assertFalse(delayBody.late());
        assertEquals(120, delayBody.totalExtensionMinutes());
        assertEquals(Instant.parse("2026-11-01T18:00:00Z"), delayBody.dutyEnd());

        // 4) 封板：终态成功。
        ResponseEntity<SealResponse> sealResp = restTemplate.exchange(
                url("/api/legality/batches/" + batchId + "/seal"),
                HttpMethod.POST, HttpEntity.EMPTY, SealResponse.class);
        assertEquals(org.springframework.http.HttpStatus.OK, sealResp.getStatusCode());
        assertTrue(sealResp.getBody().sealed());
        assertFalse(sealResp.getBody().idempotentReplay());

        // 5) 重复封板：幂等成功。
        ResponseEntity<SealResponse> resealResp = restTemplate.exchange(
                url("/api/legality/batches/" + batchId + "/seal"),
                HttpMethod.POST, HttpEntity.EMPTY, SealResponse.class);
        assertEquals(HttpStatus.OK, resealResp.getStatusCode());
        assertTrue(resealResp.getBody().idempotentReplay());

        // 6) 封板后补交延误：迟到留痕，结论不被推翻。
        DelayRequest lateDelay = new DelayRequest("api-delay-late", "api-req-1", 60, "封板后补报");
        ResponseEntity<DelayResponse> lateResp = restTemplate.postForEntity(
                url("/api/legality/delays"), lateDelay, DelayResponse.class);
        assertEquals(HttpStatus.CREATED, lateResp.getStatusCode());
        assertTrue(lateResp.getBody().late());
        assertFalse(lateResp.getBody().accepted());
        assertEquals(120, lateResp.getBody().totalExtensionMinutes());
    }

    @Test
    void sealUnknownBatch_returns404() {
        ResponseEntity<Map> resp = restTemplate.exchange(
                url("/api/legality/batches/no-such-batch/seal"),
                HttpMethod.POST, HttpEntity.EMPTY, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("NOT_FOUND", resp.getBody().get("error"));
    }

    @Test
    void delayForUnknownDuty_returns404() {
        DelayRequest req = new DelayRequest("api-delay-x", "no-such-duty", 30, null);
        ResponseEntity<Map> resp = restTemplate.postForEntity(url("/api/legality/delays"), req, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void invalidPayload_returns400() {
        MemberDutyRequest bad = new MemberDutyRequest(
                "", "C001", "UT001",
                Instant.parse("2026-11-01T06:00:00Z"),
                Instant.parse("2026-11-01T16:00:00Z"));
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                url("/api/legality/checks"), new CheckRequest(List.of(bad)), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }
}
