package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.example.starter.cems.testsupport.TestClockConfiguration;

/**
 * 真实 HTTP/JSON 端到端：许可登记→在线监测→超标标记→限排执行→复测解除的合成主流程。
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(TestClockConfiguration.class)
class CemsHttpFlowTest extends AbstractCemsTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + "/api/cems" + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> body(ResponseEntity<String> response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.getBody(), java.util.Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void 完整合成主流程_http_json() {
        // 1) 许可登记：日均限值 10，小时限值 5
        String registerJson = """
                {"requestId":"reg-http","outfallCode":"OUT-HTTP","outfallName":"合成排口",
                 "dailyLimit":10,"hourlyLimit":5}
                """;
        ResponseEntity<String> registered = rest.postForEntity(url("/outfalls"),
                new HttpEntity<>(registerJson, jsonHeaders()), String.class);
        assertEquals(HttpStatus.CREATED, registered.getStatusCode());
        assertEquals(false, body(registered).get("replayed"));
        assertEquals("REGISTERED",
                ((java.util.Map<?, ?>) body(registered).get("result")).get("status"));

        // 2) 日均值恰等限值：达标，无标记
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T10:05:00");
        ResponseEntity<String> equalDay = postReading(
                "day-equal", "DAY", "2026-09-20T00:00:00", "10");
        assertEquals(HttpStatus.OK, equalDay.getStatusCode());
        assertTrue(((java.util.Map<?, ?>) body(equalDay).get("result")).get("mark") == null);

        // 3) 三次小时均值超标 → 第 3 次自动下达限排令
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T11:05:00");
        postReading("h-09", "HOUR", "2026-09-20T09:00:00", "6");
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T12:05:00");
        postReading("h-10", "HOUR", "2026-09-20T10:00:00", "7");
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T13:05:00");
        ResponseEntity<String> third = postReading("h-11", "HOUR", "2026-09-20T11:00:00", "8");
        java.util.Map<?, ?> order = (java.util.Map<?, ?>)
                ((java.util.Map<?, ?>) body(third).get("result")).get("order");
        assertNotNull(order);
        assertEquals("ISSUED", order.get("status"));

        // 4) 2 小时内确认执行
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T14:00:00");
        String confirmJson = """
                {"requestId":"confirm-1","outfallCode":"OUT-HTTP"}""";
        ResponseEntity<String> confirmed = rest.postForEntity(url("/orders/confirm"),
                new HttpEntity<>(confirmJson, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, confirmed.getStatusCode());
        java.util.Map<?, ?> confirmResult = (java.util.Map<?, ?>) body(confirmed).get("result");
        assertEquals(false, confirmResult.get("escalated"));
        assertEquals(true, ((java.util.Map<?, ?>) confirmResult.get("order")).get("confirmed"));

        // 5) 复测恰等限值合格 → 解除并关闭限排令
        TestClockConfiguration.CLOCK.setLocal("2026-09-20T15:05:00");
        String releaseJson = """
                {"requestId":"release-1","outfallCode":"OUT-HTTP",
                 "periodStart":"2026-09-20T15:00:00","avgValue":5}""";
        ResponseEntity<String> released = rest.postForEntity(url("/orders/release"),
                new HttpEntity<>(releaseJson, jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, released.getStatusCode());
        java.util.Map<?, ?> releaseResult = (java.util.Map<?, ?>) body(released).get("result");
        assertEquals("CLOSED", ((java.util.Map<?, ?>) releaseResult.get("order")).get("status"));
        assertEquals("MONITORING", ((java.util.Map<?, ?>) releaseResult.get("outfall")).get("status"));

        // 6) 同键异参 409
        String conflicting = """
                {"requestId":"reg-http","outfallCode":"OUT-HTTP","outfallName":"合成排口",
                 "dailyLimit":99,"hourlyLimit":5}
                """;
        ResponseEntity<String> conflict = rest.postForEntity(url("/outfalls"),
                new HttpEntity<>(conflicting, jsonHeaders()), String.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 7) 排口限值保持首次结果不变
        ResponseEntity<String> outfall = rest.getForEntity(url("/outfalls/OUT-HTTP"), String.class);
        assertEquals(0, new java.math.BigDecimal("10").compareTo(
                new java.math.BigDecimal(((Number) body(outfall).get("dailyLimit")).toString())));
        assertFalse(Boolean.TRUE.equals(body(outfall).get("archived")));
    }

    private ResponseEntity<String> postReading(String requestId, String periodType,
                                               String periodStart, String avgValue) {
        String json = "{\"requestId\":\"" + requestId + "\",\"outfallCode\":\"OUT-HTTP\","
                + "\"periodType\":\"" + periodType + "\",\"periodStart\":\"" + periodStart
                + "\",\"avgValue\":" + avgValue + "}";
        return rest.postForEntity(url("/readings"),
                new HttpEntity<>(json, jsonHeaders()), String.class);
    }
}
