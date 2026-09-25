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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经真实 HTTP 入口（随机端口）验证主流程与错误语义（201/200/409/400）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestClockConfiguration.class)
class GasCustodyHttpTest {

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
        DatabaseFixtures.addNomination(jdbc, LocalDate.of(2026, 9, 20), "380000.00");
    }

    @Test
    void httpFlow_readingVoucherConfirmAssess_andConflictSemantics() {
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        Map<String, Object> reading = Map.of(
                "requestId", "http-r1",
                "stationCode", "ST01",
                "meterTime", "2026-09-20T12:00:00Z",
                "volume", "10000",
                "heatingValue", "39.200000");
        ResponseEntity<Map> readingResp = restTemplate.postForEntity(
                url("/api/readings"), reading, Map.class);
        assertThat(readingResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(readingResp.getBody()).containsEntry("duplicated", false);
        assertThat(readingResp.getBody()).containsEntry("gasDate", "2026-09-20");

        // 同站同表底时刻以另一 requestId 提交：去重 duplicated=true
        Map<String, Object> readingDup = Map.of(
                "requestId", "http-r2",
                "stationCode", "ST01",
                "meterTime", "2026-09-20T12:00:00Z",
                "volume", "10000",
                "heatingValue", "39.200000");
        ResponseEntity<Map> dupResp = restTemplate.postForEntity(
                url("/api/readings"), readingDup, Map.class);
        assertThat(dupResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dupResp.getBody()).containsEntry("duplicated", true);

        // 气量日未结束生成凭证：400
        Map<String, Object> voucherReq = Map.of(
                "requestId", "http-v1", "stationCode", "ST01",
                "gasDate", "2026-09-20");
        ResponseEntity<Map> early = restTemplate.postForEntity(
                url("/api/vouchers"), voucherReq, Map.class);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(early.getBody()).containsEntry("code", "GAS_DAY_NOT_CLOSED");

        // 24:00 后生成凭证：201
        clock.setInstant(Instant.parse("2026-09-21T00:10:00Z"));
        ResponseEntity<Map> voucherResp = restTemplate.postForEntity(
                url("/api/vouchers"), voucherReq, Map.class);
        assertThat(voucherResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(voucherResp.getBody()).containsEntry("status", "DRAFT");
        assertThat(voucherResp.getBody()).containsEntry("energy", 392000.00);
        String voucherNo = (String) voucherResp.getBody().get("voucherNo");

        // 同站同日再生成：409
        Map<String, Object> voucherReq2 = Map.of(
                "requestId", "http-v2", "stationCode", "ST01",
                "gasDate", "2026-09-20");
        ResponseEntity<Map> conflict = restTemplate.postForEntity(
                url("/api/vouchers"), voucherReq2, Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).containsEntry("code", "VOUCHER_ALREADY_EXISTS");

        // 双方确认
        ResponseEntity<Map> aResp = restTemplate.postForEntity(
                url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "http-c1", "party", "A"), Map.class);
        assertThat(aResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aResp.getBody()).containsEntry("status", "PARTIALLY_CONFIRMED");
        ResponseEntity<Map> bResp = restTemplate.postForEntity(
                url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "http-c2", "party", "B"), Map.class);
        assertThat(bResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bResp.getBody()).containsEntry("status", "EFFECTIVE");

        // 偏差考核：指定 380000，实际 392000，偏差 12000，阈值 11400，违约金 (600)×0.5=300
        ResponseEntity<Map> assessResp = restTemplate.postForEntity(
                url("/api/stations/ST01/assessments?gasDate=2026-09-20"),
                null, Map.class);
        assertThat(assessResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assessResp.getBody()).containsEntry("exempt", false);
        assertThat(assessResp.getBody()).containsEntry("penalty", 300.00);

        // 校验失败（party 非法）：400
        ResponseEntity<Map> badParty = restTemplate.postForEntity(
                url("/api/vouchers/" + voucherNo + "/confirmations"),
                Map.of("requestId", "http-bad", "party", "C"), Map.class);
        assertThat(badParty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
