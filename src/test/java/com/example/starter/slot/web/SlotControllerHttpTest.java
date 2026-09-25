package com.example.starter.slot.web;

import com.example.starter.slot.FixedClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时刻协调真实 HTTP 入口测试：状态码、冲突清单与请求体校验。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockTestConfig.class)
class SlotControllerHttpTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM slot_snapshot");
        jdbcTemplate.update("DELETE FROM slot_allocation");
        jdbcTemplate.update("DELETE FROM slot_series");
        jdbcTemplate.update("DELETE FROM slot_season");
        jdbcTemplate.update("DELETE FROM historical_performance");
        jdbcTemplate.update("INSERT INTO slot_season (season, frozen, frozen_at, version, created_at) VALUES ('S26', 0, NULL, 0, 0)");
        jdbcTemplate.update("INSERT INTO historical_performance (prev_season, airline, flight_no, airport, slot_time, execution_rate) VALUES ('S25', 'CA', '1001', 'PEK', '08:00', 80.0)");
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> applyBody(String requestId, String airline, String flightNo,
                                          String airport, String slotTime, List<Integer> weeks) {
        return Map.of(
                "requestId", requestId,
                "season", "S26",
                "airline", airline,
                "flightNo", flightNo,
                "airport", airport,
                "slotTime", slotTime,
                "weeks", weeks);
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyRetainedThenConflictRejectsWithConflictList() {
        ResponseEntity<Map> first = restTemplate.postForEntity(
                "/api/slots/applications",
                json(applyBody("HTTP-1", "CA", "1001", "PEK", "08:00", List.of(1, 2))),
                Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("decision", "RETAINED");

        ResponseEntity<Map> second = restTemplate.postForEntity(
                "/api/slots/applications",
                json(applyBody("HTTP-2", "MU", "2001", "PEK", "08:00", List.of(2))),
                Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("error", "slot_conflict");
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) second.getBody().get("conflicts");
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0)).containsEntry("slotTime", "08:00");
        assertThat(conflicts.get(0)).containsEntry("holderAirline", "CA");
        assertThat(conflicts.get(0)).containsEntry("holderRequestId", "HTTP-1");
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        restTemplate.postForEntity(
                "/api/slots/applications",
                json(applyBody("HTTP-IDEM", "CA", "1001", "PEK", "08:00", List.of(1))),
                Map.class);

        ResponseEntity<Map> mismatch = restTemplate.postForEntity(
                "/api/slots/applications",
                json(applyBody("HTTP-IDEM", "CA", "1001", "PEK", "08:10", List.of(1))),
                Map.class);

        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mismatch.getBody()).containsEntry("error", "fingerprint_mismatch");
    }

    @Test
    void releaseUnknownRequestIdReturns404() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/slots/releases",
                json(Map.of("requestId", "NO-SUCH")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "not_found");
    }

    @Test
    void invalidWeeksReturns400() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/slots/applications",
                json(applyBody("HTTP-BAD", "CA", "1001", "PEK", "08:00", List.of())),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "validation_error");
    }

    @Test
    void publishThenApplyReturns409StateConflict() {
        restTemplate.postForEntity(
                "/api/slots/applications",
                json(applyBody("HTTP-PUB", "CA", "1001", "PEK", "08:00", List.of(1))),
                Map.class);

        ResponseEntity<Map> publish = restTemplate.postForEntity(
                "/api/slots/publications",
                json(Map.of("season", "S26")),
                Map.class);
        assertThat(publish.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publish.getBody()).containsEntry("alreadyPublished", false);

        ResponseEntity<Map> republish = restTemplate.postForEntity(
                "/api/slots/publications",
                json(Map.of("season", "S26")),
                Map.class);
        assertThat(republish.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(republish.getBody()).containsEntry("alreadyPublished", true);

        ResponseEntity<Map> lateApply = restTemplate.postForEntity(
                "/api/slots/applications",
                json(applyBody("HTTP-LATE", "CZ", "3001", "PEK", "10:00", List.of(1))),
                Map.class);
        assertThat(lateApply.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(lateApply.getBody()).containsEntry("error", "state_conflict");
    }
}
