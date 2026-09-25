package com.example.starter.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.api.dto.ConfirmCommandRequest;
import com.example.starter.api.dto.DeclarePlanRequest;
import com.example.starter.api.dto.EvaluateHourRequest;
import com.example.starter.api.dto.FuelComponentRequest;
import com.example.starter.api.dto.LicenseUpsertRequest;
import com.example.starter.api.dto.ReadingBatchRequest;
import com.example.starter.api.dto.ReadingItem;
import com.example.starter.api.dto.ReviewDecision;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfiguration;
import com.example.starter.support.TestFixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 端到端 HTTP 流转测试：申报→审核→执行→读数→超标降掺→确认→日结，
 * 校验真实序列化与 201/200/422/409 状态码，全程使用嵌入式 H2。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
class CoFiringFlowHttpTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @BeforeEach
    void clean() {
        TestFixtures.cleanAll(jdbc);
        clock.setTime(LocalDateTime.of(2026, 9, 25, 10, 0, 0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<String> response) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(response.getBody(), Map.class);
    }

    @Test
    void fullFlowThroughSettlementOverHttp() throws Exception {
        // 1. 合成许可
        ResponseEntity<String> licenseResp = rest.postForEntity(url("/api/licenses"),
                new LicenseUpsertRequest("LIC-WEB", "网络流转让持证单位",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false), String.class);
        assertThat(licenseResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 2. 申报（恰 40%）
        DeclarePlanRequest declare = new DeclarePlanRequest("web-req-1", "KILN-WEB", "LIC-WEB",
                new BigDecimal("0.4000"), new BigDecimal("0.1000"), List.of(
                        new FuelComponentRequest("煤粉", false, new BigDecimal("1000"),
                                new BigDecimal("5500"), new BigDecimal("0.01"), new BigDecimal("0.30")),
                        new FuelComponentRequest("RDF", true, new BigDecimal("1000"),
                                new BigDecimal("3200"), new BigDecimal("0.01"), new BigDecimal("0.30"))));
        ResponseEntity<String> declaredResp = rest.postForEntity(url("/api/plans"), declare, String.class);
        assertThat(declaredResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> declaredBody = body(declaredResp);
        assertThat(declaredBody.get("replayed")).isEqualTo(false);
        long planId = ((Number) ((Map<?, ?>) declaredBody.get("data")).get("id")).longValue();

        // 3. 审核通过
        ResponseEntity<String> reviewResp = rest.postForEntity(
                url("/api/plans/" + planId + "/review"),
                new ReviewRequest(ReviewDecision.APPROVE, null), String.class);
        assertThat(reviewResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(reviewResp).get("status")).isEqualTo("APPROVED");

        // 4. 执行
        ResponseEntity<String> execResp = rest.postForEntity(
                url("/api/plans/" + planId + "/execute"), null, String.class);
        assertThat(execResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(execResp).get("status")).isEqualTo("EXECUTING");

        // 5. 上报 09 时超标读数
        ReadingBatchRequest readings = new ReadingBatchRequest(List.of(
                new ReadingItem("P1", "NOX", LocalDateTime.of(2026, 9, 25, 9, 20),
                        new BigDecimal("450"))));
        ResponseEntity<String> ingestResp = rest.postForEntity(
                url("/api/plans/" + planId + "/readings"), readings, String.class);
        assertThat(ingestResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(ingestResp).get("accepted")).isEqualTo(1);

        // 6. 小时评估：超标并下发降掺指令
        ResponseEntity<String> evalResp = rest.postForEntity(
                url("/api/plans/" + planId + "/evaluations"),
                new EvaluateHourRequest(LocalDateTime.of(2026, 9, 25, 9, 0)), String.class);
        assertThat(evalResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> evalBody = body(evalResp);
        assertThat(evalBody.get("exceeded")).isEqualTo(true);
        Map<?, ?> command = (Map<?, ?>) evalBody.get("command");
        assertThat(command.get("status")).isEqualTo("ISSUED");
        long commandId = ((Number) command.get("id")).longValue();

        // 7. 下发 20 分钟内确认：CONFIRMED
        clock.setTime(LocalDateTime.of(2026, 9, 25, 10, 20, 0));
        ResponseEntity<String> confirmResp = rest.postForEntity(
                url("/api/plans/" + planId + "/commands/" + commandId + "/confirm"),
                new ConfirmCommandRequest("web-confirm-1"), String.class);
        assertThat(confirmResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) body(confirmResp).get("data")).get("status")).isEqualTo("CONFIRMED");

        // 8. 日结封存
        ResponseEntity<String> sealResp = rest.postForEntity(
                url("/api/plans/" + planId + "/settlement?date=2026-09-25"), null, String.class);
        assertThat(sealResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> settlement = body(sealResp);
        assertThat(settlement.get("sealed")).isEqualTo(true);
        assertThat(((Number) settlement.get("actualHeatRatio")).doubleValue()).isEqualTo(0.4d);
        assertThat(((List<?>) settlement.get("exceedancePeriods"))).hasSize(1);
        assertThat(((List<?>) settlement.get("reductionCommands"))).hasSize(1);

        // 9. 重复封存幂等
        ResponseEntity<String> replaySeal = rest.postForEntity(
                url("/api/plans/" + planId + "/settlement?date=2026-09-25"), null, String.class);
        assertThat(replaySeal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(replaySeal).get("sealed")).isEqualTo(true);

        // 10. 封存后读数只记迟到
        ResponseEntity<String> lateResp = rest.postForEntity(
                url("/api/plans/" + planId + "/readings"), readings, String.class);
        assertThat(body(lateResp).get("late")).isEqualTo(1);
    }

    @Test
    void rejectedReviewReturns422AndSameKeyDifferentParamsReturns409OverHttp() throws Exception {
        rest.postForEntity(url("/api/licenses"),
                new LicenseUpsertRequest("LIC-WEB2", "持证单位",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 24), false), String.class);

        DeclarePlanRequest declare = new DeclarePlanRequest("web-req-2", "KILN-WEB2", "LIC-WEB2",
                new BigDecimal("0.4000"), new BigDecimal("0.1000"), List.of(
                        new FuelComponentRequest("煤粉", false, new BigDecimal("1000"),
                                new BigDecimal("5500"), new BigDecimal("0.01"), new BigDecimal("0.30"))));
        ResponseEntity<String> declaredResp = rest.postForEntity(url("/api/plans"), declare, String.class);
        long planId = ((Number) ((Map<?, ?>) body(declaredResp).get("data")).get("id")).longValue();

        // 许可过期：审核整单拒绝 422
        ResponseEntity<String> reviewResp = rest.postForEntity(
                url("/api/plans/" + planId + "/review"),
                new ReviewRequest(ReviewDecision.APPROVE, null), String.class);
        assertThat(reviewResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(reviewResp).get("code")).isEqualTo("PLAN_REJECTED");

        // 同键异参：409
        DeclarePlanRequest tampered = new DeclarePlanRequest("web-req-2", "KILN-WEB2", "LIC-WEB2",
                new BigDecimal("0.3000"), new BigDecimal("0.1000"), List.of(
                        new FuelComponentRequest("煤粉", false, new BigDecimal("1000"),
                                new BigDecimal("5500"), new BigDecimal("0.01"), new BigDecimal("0.30"))));
        ResponseEntity<String> conflictResp = rest.postForEntity(url("/api/plans"), tampered, String.class);
        assertThat(conflictResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(conflictResp).get("code")).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void exactly30MinuteUnconfirmedCommandAutoEscalatesOverHttp() throws Exception {
        rest.postForEntity(url("/api/licenses"),
                new LicenseUpsertRequest("LIC-WEB3", "持证单位",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false), String.class);
        DeclarePlanRequest declare = new DeclarePlanRequest("web-req-3", "KILN-WEB3", "LIC-WEB3",
                new BigDecimal("0.4000"), new BigDecimal("0.1000"), List.of(
                        new FuelComponentRequest("煤粉", false, new BigDecimal("1000"),
                                new BigDecimal("5500"), new BigDecimal("0.01"), new BigDecimal("0.30"))));
        ResponseEntity<String> declaredResp = rest.postForEntity(url("/api/plans"), declare, String.class);
        long planId = ((Number) ((Map<?, ?>) body(declaredResp).get("data")).get("id")).longValue();
        rest.postForEntity(url("/api/plans/" + planId + "/review"),
                new ReviewRequest(ReviewDecision.APPROVE, null), String.class);
        rest.postForEntity(url("/api/plans/" + planId + "/execute"), null, String.class);
        rest.postForEntity(url("/api/plans/" + planId + "/readings"),
                new ReadingBatchRequest(List.of(new ReadingItem("P1", "NOX",
                        LocalDateTime.of(2026, 9, 25, 9, 20), new BigDecimal("500")))), String.class);
        ResponseEntity<String> evalResp = rest.postForEntity(url("/api/plans/" + planId + "/evaluations"),
                new EvaluateHourRequest(LocalDateTime.of(2026, 9, 25, 9, 0)), String.class);
        long commandId = ((Number) ((Map<?, ?>) body(evalResp).get("command")).get("id")).longValue();

        // 恰满 30 分钟（10:30:00）才确认：指令已升级，返回 409，方案强制保底。
        clock.setTime(LocalDateTime.of(2026, 9, 25, 10, 30, 0));
        ResponseEntity<String> lateConfirm = rest.postForEntity(
                url("/api/plans/" + planId + "/commands/" + commandId + "/confirm"),
                new ConfirmCommandRequest("web-confirm-late"), String.class);
        assertThat(lateConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(lateConfirm).get("code")).isEqualTo("COMMAND_ALREADY_ESCALATED");

        ResponseEntity<String> planResp = rest.getForEntity(url("/api/plans/" + planId), String.class);
        assertThat(((Number) ((Map<?, ?>) body(planResp)).get("currentHeatRatio")).doubleValue())
                .isEqualTo(0.1d);
    }
}
