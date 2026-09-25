package com.example.starter.runway;

import com.example.starter.runway.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地启动入口冒烟测试：真实 HTTP 端口上完成 接报→巡检→评估→恢复→归档 全链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunwayApplicationSmokeTest {

    private static final Instant T0 = Instant.parse("2026-09-26T07:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM notam_record");
        jdbc.update("DELETE FROM assessment_record");
        jdbc.update("DELETE FROM inspection_record");
        jdbc.update("DELETE FROM runway_event");
        jdbc.update("DELETE FROM weather_reading");
    }

    @Test
    void fullLifecycleOverHttp() {
        String base = "http://localhost:" + port + "/api";

        var reportResp = restTemplate.postForEntity(base + "/runway-events/reports",
                new ReportRequest("smoke-1", "RW90", EventType.FOD, T0), ReportResponse.class);
        assertEquals(HttpStatus.CREATED, reportResp.getStatusCode());
        ReportResponse reported = reportResp.getBody();
        assertNotNull(reported);
        assertEquals("CLOSED", reported.status());
        long eventId = reported.eventId();

        var inspectionResp = restTemplate.postForEntity(
                base + "/runway-events/" + eventId + "/inspections",
                new InspectionRequest(T0.plus(Duration.ofMinutes(5)), 2), InspectionResponse.class);
        assertEquals(HttpStatus.CREATED, inspectionResp.getStatusCode());
        assertFalse(inspectionResp.getBody().late());

        var assessmentResp = restTemplate.postForEntity(
                base + "/runway-events/" + eventId + "/assessments",
                new AssessmentRequest("smoke-a", new BigDecimal("0.42"), AssessmentType.CLEAR_TO_REOPEN,
                        T0.plus(Duration.ofMinutes(8))), AssessmentResponse.class);
        assertTrue(assessmentResp.getBody().passed());

        var reopenResp = restTemplate.postForEntity(
                base + "/runway-events/" + eventId + "/reopen",
                new ReopenRequest("smoke-r", T0.plus(Duration.ofMinutes(10))), ReopenResponse.class);
        assertEquals(HttpStatus.OK, reopenResp.getStatusCode());
        assertNotNull(reopenResp.getBody().notamNo());

        var archiveResp = restTemplate.postForEntity(
                base + "/runway-events/" + eventId + "/archive", null, ArchiveResponse.class);
        assertEquals(HttpStatus.OK, archiveResp.getStatusCode());
        assertEquals("ARCHIVED", archiveResp.getBody().status());

        NotamView[] notams = restTemplate.getForObject(
                base + "/runway-events/" + eventId + "/notams", NotamView[].class);
        assertEquals(2, notams.length);
        FrictionPoint[] points = restTemplate.getForObject(
                base + "/runway-events/" + eventId + "/friction-trajectory", FrictionPoint[].class);
        assertEquals(1, points.length);
        EventDetailResponse detail = restTemplate.getForObject(
                base + "/runway-events/" + eventId, EventDetailResponse.class);
        assertEquals("ARCHIVED", detail.status());
        assertFalse(detail.timeline().isEmpty());
    }
}
