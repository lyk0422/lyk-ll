package com.example.starter.gas;

import com.example.starter.gas.support.DatabaseFixtures;
import com.example.starter.gas.support.MutableClock;
import com.example.starter.gas.support.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 气质组分核验 REST 入口流程：提交核验（组分 JSON）→ 差异暂停确认 → 双方签认 → 解除。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfiguration.class)
class QualityVerificationHttpTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeEach
    void setUp() {
        DatabaseFixtures.reset(jdbc);
        DatabaseFixtures.addNomination(jdbc, LocalDate.of(2026, 9, 20), "400000.00");
    }

    @Test
    void verificationDifference_pausesConfirmUntilBothSignOff() {
        // 读数 → 凭证（热值 40.000000）
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        ResponseEntity<Map> reading = restTemplate.postForEntity(url("/api/readings"),
                Map.of("requestId", "h-r1", "stationCode", "ST01",
                        "meterTime", "2026-09-20T12:00:00Z",
                        "volume", "10000", "heatingValue", "40.000000"), Map.class);
        assertThat(reading.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        ResponseEntity<Map> voucher = restTemplate.postForEntity(url("/api/vouchers"),
                Map.of("requestId", "h-v1", "stationCode", "ST01", "gasDate", "2026-09-20"),
                Map.class);
        assertThat(voucher.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String voucherNo = (String) voucher.getBody().get("voucherNo");

        // 提交核验：97% CH4 + 3% C2H6 → 核验热值 40.6，偏差 1.5% > 1% → 气质差异
        ResponseEntity<Map> verification = restTemplate.postForEntity(url("/api/verifications"),
                Map.of("requestId", "h-q1", "stationCode", "ST01", "gasDate", "2026-09-20",
                        "samples", List.of(Map.of(
                                "sampleTime", "2026-09-20T10:00:00Z",
                                "sampleVolume", "500",
                                "components", Map.of("CH4", "97", "C2H6", "3")))),
                Map.class);
        assertThat(verification.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(verification.getBody()).containsEntry("differenceFlag", true);
        assertThat(verification.getBody()).containsEntry("status", "DIFFERENT_OPEN");
        assertThat(verification.getBody()).containsEntry("confirmPaused", true);
        assertThat(verification.getBody()).containsEntry("version", 1);
        String verificationNo = (String) verification.getBody().get("verificationNo");

        // 差异未解决：确认被暂停（409）
        ResponseEntity<Map> confirm = restTemplate.postForEntity(
                url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "h-c1", "party", "A"), Map.class);
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirm.getBody()).containsEntry("code", "CONFIRM_PAUSED");

        // 双方签认差异
        ResponseEntity<Map> signA = restTemplate.postForEntity(
                url("/api/verifications/" + verificationNo + "/sign-offs"),
                Map.of("requestId", "h-sa", "party", "A"), Map.class);
        assertThat(signA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signA.getBody()).containsEntry("status", "DIFFERENT_OPEN");
        ResponseEntity<Map> signB = restTemplate.postForEntity(
                url("/api/verifications/" + verificationNo + "/sign-offs"),
                Map.of("requestId", "h-sb", "party", "B"), Map.class);
        assertThat(signB.getBody()).containsEntry("status", "DIFFERENT_RESOLVED");
        assertThat(signB.getBody()).containsEntry("confirmPaused", false);

        // 解除暂停后双方确认生效
        ResponseEntity<Map> confirmA = restTemplate.postForEntity(
                url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "h-c2", "party", "A"), Map.class);
        assertThat(confirmA.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> confirmB = restTemplate.postForEntity(
                url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "h-c3", "party", "B"), Map.class);
        assertThat(confirmB.getBody()).containsEntry("status", "EFFECTIVE");

        // 旧版本结论可查
        ResponseEntity<List> history = restTemplate.getForEntity(
                url("/api/stations/ST01/verifications?gasDate=2026-09-20"), List.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).hasSize(1);
    }

    @Test
    void ratioNotHundred_returns400() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        restTemplate.postForEntity(url("/api/readings"),
                Map.of("requestId", "h-r1", "stationCode", "ST01",
                        "meterTime", "2026-09-20T12:00:00Z",
                        "volume", "10000", "heatingValue", "40.000000"), Map.class);
        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        restTemplate.postForEntity(url("/api/vouchers"),
                Map.of("requestId", "h-v1", "stationCode", "ST01", "gasDate", "2026-09-20"),
                Map.class);

        ResponseEntity<Map> rejected = restTemplate.postForEntity(url("/api/verifications"),
                Map.of("requestId", "h-q-bad", "stationCode", "ST01", "gasDate", "2026-09-20",
                        "samples", List.of(Map.of(
                                "sampleTime", "2026-09-20T10:00:00Z",
                                "sampleVolume", "500",
                                "components", Map.of("CH4", "90", "C2H6", "5")))),
                Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).containsEntry("code", "COMPONENT_RATIO_NOT_100");
    }
}
