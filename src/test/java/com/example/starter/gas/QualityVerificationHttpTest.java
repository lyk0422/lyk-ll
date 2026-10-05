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
 * 气质组分核验的 HTTP 入口验证（随机端口）：
 * 组分核验 → 气质差异 → 确认暂停 409 → 双方签认 → 确认生效；比例勾稽 400。
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
    void httpFlow_verificationDiffPausesConfirmation_signOffReleases() {
        // 读数 → 凭证（热值 40.000000）
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        ResponseEntity<Map> reading = restTemplate.postForEntity(url("/api/readings"), Map.of(
                "requestId", "h-r1", "stationCode", "ST01",
                "meterTime", "2026-09-20T12:00:00Z",
                "volume", "10000", "heatingValue", "40.000000"), Map.class);
        assertThat(reading.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        clock.setInstant(Instant.parse("2026-09-21T00:05:00Z"));
        ResponseEntity<Map> voucher = restTemplate.postForEntity(url("/api/vouchers"), Map.of(
                "requestId", "h-v1", "stationCode", "ST01", "gasDate", "2026-09-20"), Map.class);
        assertThat(voucher.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String voucherNo = (String) voucher.getBody().get("voucherNo");

        // 比例合计 99%：400 拒绝入账
        ResponseEntity<Map> badRatio = restTemplate.postForEntity(url("/api/verifications"), Map.of(
                "requestId", "h-q0", "stationCode", "ST01", "gasDate", "2026-09-20",
                "voucherNo", voucherNo,
                "samples", List.of(Map.of("sampleTime", "2026-09-20T08:00:00Z",
                        "sampleVolume", "100",
                        "components", Map.of("CH4", "90", "C2H6", "9")))), Map.class);
        assertThat(badRatio.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRatio.getBody()).containsEntry("code", "COMPONENT_RATIO_INVALID");

        // 气质差异核验（CH4 89/C2H6 11 → 40.44，偏差 1.1%）：201，暂停确认
        ResponseEntity<Map> verification = restTemplate.postForEntity(url("/api/verifications"),
                Map.of("requestId", "h-q1", "stationCode", "ST01", "gasDate", "2026-09-20",
                        "voucherNo", voucherNo,
                        "samples", List.of(Map.of("sampleTime", "2026-09-20T08:00:00Z",
                                "sampleVolume", "100",
                                "components", Map.of("CH4", "89", "C2H6", "11")))), Map.class);
        assertThat(verification.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(verification.getBody()).containsEntry("conclusion", "QUALITY_DIFF");
        assertThat(verification.getBody()).containsEntry("confirmationPaused", true);
        String verificationNo = (String) verification.getBody().get("verificationNo");

        // 差异未解决：双方确认被暂停（409）
        ResponseEntity<Map> paused = restTemplate.postForEntity(
                url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "h-c1", "party", "A"), Map.class);
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(paused.getBody()).containsEntry("code", "CONFIRMATION_PAUSED");

        // 双方签认差异
        ResponseEntity<Map> signA = restTemplate.postForEntity(
                url("/api/verifications/" + verificationNo + "/sign-offs"),
                Map.of("requestId", "h-s1", "party", "A"), Map.class);
        assertThat(signA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signA.getBody()).containsEntry("diffStatus", "OPEN");
        ResponseEntity<Map> signB = restTemplate.postForEntity(
                url("/api/verifications/" + verificationNo + "/sign-offs"),
                Map.of("requestId", "h-s2", "party", "B"), Map.class);
        assertThat(signB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signB.getBody()).containsEntry("diffStatus", "RESOLVED");
        assertThat(signB.getBody()).containsEntry("confirmationPaused", false);

        // 签认后双方确认生效
        restTemplate.postForEntity(url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "h-c2", "party", "A"), Map.class);
        ResponseEntity<Map> effective = restTemplate.postForEntity(
                url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "h-c3", "party", "B"), Map.class);
        assertThat(effective.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(effective.getBody()).containsEntry("status", "EFFECTIVE");

        // 版本列表可查（含本次生效版本）
        ResponseEntity<List> versions = restTemplate.getForEntity(
                url("/api/stations/ST01/verifications?gasDate=2026-09-20"), List.class);
        assertThat(versions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versions.getBody()).hasSize(1);
    }
}
