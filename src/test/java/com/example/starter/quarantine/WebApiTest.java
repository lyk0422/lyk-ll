package com.example.starter.quarantine;

import com.example.starter.quarantine.api.dto.BlockadeRequest;
import com.example.starter.quarantine.api.dto.DeclareRequest;
import com.example.starter.quarantine.api.dto.LandingReportRequest;
import com.example.starter.quarantine.domain.AnimalType;
import com.example.starter.quarantine.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 经真实 HTTP 入口（随机端口）验证状态码契约与完整链路：200/409/422/400。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class WebApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String url(String path) {
        return "http://localhost:" + port + "/api/quarantine" + path;
    }

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM landing_report");
        jdbcTemplate.update("DELETE FROM quarantine_certificate");
        jdbcTemplate.update("DELETE FROM quarantine_request");
        jdbcTemplate.update("DELETE FROM blockade");
        jdbcTemplate.update("DELETE FROM idempotent_command");
        jdbcTemplate.update("UPDATE farm_stock SET available_count = total_count, version = 0");
        jdbcTemplate.update("ALTER SEQUENCE cert_no_seq RESTART WITH 1");
    }

    @org.junit.jupiter.api.Test
    void httpFullFlow_declareIssueBlockadeOtherFarmLanding_returns200() {
        var declare = new DeclareRequest("WEB-1", "FARM-001", "DEST-WEB",
                AnimalType.CATTLE, "TAG-C-0001-0100", 10);
        ResponseEntity<String> declared = post("/declarations", declare, String.class);
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.OK, declared.getStatusCode());

        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.OK,
                post("/certificates", record("WEB-1"), String.class).getStatusCode());
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.OK,
                post("/departures", record("WEB-1"), String.class).getStatusCode());

        // 封锁另一个养殖场不影响本单。
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.OK,
                post("/blockades", new BlockadeRequest("WEB-BLK", "FARM-002", "禽流感疫点"),
                        String.class).getStatusCode());

        var landing = post("/landings", new LandingReportRequest("WEB-1", "WR-1", 10), String.class);
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.OK, landing.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(landing.getBody().contains("\"closed\":true"));
    }

    @org.junit.jupiter.api.Test
    void sameKeyDifferentParams_returns409() {
        post("/declarations", new DeclareRequest("WEB-409", "FARM-001", "DEST",
                AnimalType.CATTLE, "TAG-C-0001-0100", 10), String.class);
        ResponseEntity<String> conflict = post("/declarations",
                new DeclareRequest("WEB-409", "FARM-001", "DEST",
                        AnimalType.CATTLE, "TAG-C-0001-0100", 12), String.class);
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
    }

    @org.junit.jupiter.api.Test
    void issueUnknownRequest_returns422() {
        ResponseEntity<String> response = post("/certificates", record("NOT-EXIST"), String.class);
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    }

    @org.junit.jupiter.api.Test
    void invalidQuantity_returns400() {
        ResponseEntity<String> response = post("/declarations",
                new DeclareRequest("WEB-400", "FARM-001", "DEST",
                        AnimalType.CATTLE, "TAG-C-0001-0100", 0), String.class);
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    private record IssueBody(String requestId) {
    }

    private IssueBody record(String requestId) {
        return new IssueBody(requestId);
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> responseType) {
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body), responseType);
    }
}
