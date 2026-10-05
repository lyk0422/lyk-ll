package com.example.starter.pension;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 入口端到端测试：成员/缴费录入、申请受理、同键重放/异参 409、逐期支付、结清。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PensionRestApiIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.example.starter.pension.support.TimeProvider timeProvider;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : List.of(
                "late_contribution_event", "inheritance_ledger", "inheritance_claim",
                "account_ledger", "payout_instruction", "payout_installment",
                "payout_plan", "contribution", "member")) {
            jdbcTemplate.update("TRUNCATE TABLE " + table);
        }
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        timeProvider.setClock(java.time.Clock.fixed(
                java.time.Instant.parse("2026-09-25T10:15:30Z"), java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("REST 全链路：50%归属+12期尾差，逐期支付后自动结清，申请/支付同键重放")
    void restEndToEnd() {
        // 成员（恰满 5 年 -> 80%）
        ResponseEntity<Void> memberResp = rest.exchange("/api/pension/members", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "memberNo", "W1",
                        "name", "钱七",
                        "hireDate", "2021-09-25",
                        "status", "RESIGNED")), Void.class);
        assertThat(memberResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Void> c1 = rest.exchange("/api/pension/contributions", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "memberNo", "W1",
                        "contributionType", "PERSONAL",
                        "amountCents", 100,
                        "contributionDate", "2024-01-31",
                        "period", "2024-01")), Void.class);
        assertThat(c1.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Void> c2 = rest.exchange("/api/pension/contributions", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "memberNo", "W1",
                        "contributionType", "ENTERPRISE",
                        "amountCents", 100,
                        "contributionDate", "2024-02-29",
                        "period", "2024-02")), Void.class);
        assertThat(c2.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> payoutReq = Map.of(
                "requestId", "W1-REQ",
                "memberNo", "W1",
                "reason", "RESIGN",
                "installmentType", "MONTHLY_12",
                "requestDate", "2026-09-25");

        ResponseEntity<JsonNode> created = rest.postForEntity(
                "/api/pension/payouts", payoutReq, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = created.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hireYears").asInt()).isEqualTo(5);
        assertThat(body.get("vestedRatioPercent").asText()).isEqualTo("80.00");
        // 个人 100 + 企业 80 = 180，未归属 20
        assertThat(body.get("personalAmountCents").asLong()).isEqualTo(100L);
        assertThat(body.get("vestedEnterpriseCents").asLong()).isEqualTo(80L);
        assertThat(body.get("vestedTotalCents").asLong()).isEqualTo(180L);
        assertThat(body.get("unvestedReturnCents").asLong()).isEqualTo(20L);
        assertThat(body.get("installments").size()).isEqualTo(12);
        // 180/12 = 15 整除，无尾差
        for (JsonNode ins : body.get("installments")) {
            assertThat(ins.get("amountCents").asLong()).isEqualTo(15L);
        }
        String planNo = body.get("planNo").asText();

        // 同键同参重放：返回首次的同一计划（受理接口统一 201，响应体为首次结果）
        ResponseEntity<JsonNode> replay = rest.postForEntity(
                "/api/pension/payouts", payoutReq, JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().get("planNo").asText()).isEqualTo(planNo);

        // 同键异参：409
        Map<String, Object> conflictReq = Map.of(
                "requestId", "W1-REQ",
                "memberNo", "W1",
                "reason", "RESIGN",
                "installmentType", "LUMP_SUM",
                "requestDate", "2026-09-25");
        ResponseEntity<JsonNode> conflict = rest.postForEntity(
                "/api/pension/payouts", conflictReq, JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // 逐期支付前 11 期
        for (int seq = 1; seq <= 11; seq++) {
            ResponseEntity<JsonNode> pay = rest.postForEntity("/api/pension/payments",
                    Map.of("requestId", "W1-PAY-" + seq, "planNo", planNo,
                            "seqNo", seq, "forceFail", false), JsonNode.class);
            assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(pay.getBody().get("success").asBoolean()).isTrue();
        }
        // 未结清时手动结清 -> 409
        ResponseEntity<JsonNode> earlySettle = rest.postForEntity(
                "/api/pension/plans/" + planNo + "/settle", null, JsonNode.class);
        assertThat(earlySettle.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 末期支付触发划回与自动结清
        ResponseEntity<JsonNode> last = rest.postForEntity("/api/pension/payments",
                Map.of("requestId", "W1-PAY-12", "planNo", planNo,
                        "seqNo", 12, "forceFail", false), JsonNode.class);
        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> planView = rest.getForEntity(
                "/api/pension/plans/" + planNo, JsonNode.class);
        assertThat(planView.getBody().get("status").asText()).isEqualTo("SETTLED");
        assertThat(planView.getBody().get("enterpriseReturned").asBoolean()).isTrue();

        // 重复结清幂等成功
        ResponseEntity<JsonNode> settle = rest.postForEntity(
                "/api/pension/plans/" + planNo + "/settle", null, JsonNode.class);
        assertThat(settle.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(settle.getBody().get("replayed").asBoolean()).isTrue();
        assertThat(settle.getBody().get("paidTotalCents").asLong()).isEqualTo(180L);
        assertThat(settle.getBody().get("returnedTotalCents").asLong()).isEqualTo(20L);
    }

    @Test
    @DisplayName("REST 继承申领：受理 201/同键重放/异参 409/逾期 422，完成终态与迟到缴费事件")
    void restInheritanceClaim() {
        rest.exchange("/api/pension/members", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "memberNo", "W2",
                        "name", "继承 rest",
                        "hireDate", "2020-01-01",
                        "status", "RETIRED")), Void.class);
        rest.exchange("/api/pension/contributions", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "memberNo", "W2",
                        "contributionType", "PERSONAL",
                        "amountCents", 1000,
                        "contributionDate", "2024-01-31",
                        "period", "2024-01")), Void.class);

        Map<String, Object> claimReq = Map.of(
                "requestId", "W2-CLAIM",
                "memberNo", "W2",
                "heirName", "继承人 REST",
                "deathDate", "2026-06-17",
                "declaredAccountBalanceCents", 1000,
                "declaredUnpaidInstallmentCents", 0,
                "declaredClaimAmountCents", 1000);
        ResponseEntity<JsonNode> created = rest.postForEntity(
                "/api/pension/inheritance-claims", claimReq, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(created.getBody().get("claimAmountCents").asLong()).isEqualTo(1000L);
        assertThat(created.getBody().get("applyDate").asText()).isEqualTo("2026-09-25");
        String claimNo = created.getBody().get("claimNo").asText();

        // 同键同参重放：返回首次申领
        ResponseEntity<JsonNode> replay = rest.postForEntity(
                "/api/pension/inheritance-claims", claimReq, JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().get("claimNo").asText()).isEqualTo(claimNo);
        assertThat(replay.getBody().get("replayed").asBoolean()).isTrue();

        // 同键异参：409
        Map<String, Object> conflictReq = Map.of(
                "requestId", "W2-CLAIM",
                "memberNo", "W2",
                "heirName", "另一位继承人",
                "deathDate", "2026-06-17",
                "declaredAccountBalanceCents", 1000,
                "declaredUnpaidInstallmentCents", 0,
                "declaredClaimAmountCents", 1000);
        ResponseEntity<JsonNode> conflict = rest.postForEntity(
                "/api/pension/inheritance-claims", conflictReq, JsonNode.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // 逾期申领：422（2026-01-01 身故，第 180 日为 2026-06-30，早于受理日 2026-09-25）
        rest.exchange("/api/pension/members", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "memberNo", "W3",
                        "name", "逾期继承",
                        "hireDate", "2020-01-01",
                        "status", "RETIRED")), Void.class);
        ResponseEntity<JsonNode> expired = rest.postForEntity("/api/pension/inheritance-claims",
                Map.of("requestId", "W3-CLAIM",
                        "memberNo", "W3",
                        "heirName", "逾期继承人",
                        "deathDate", "2026-01-01",
                        "declaredAccountBalanceCents", 0,
                        "declaredUnpaidInstallmentCents", 0,
                        "declaredClaimAmountCents", 0), JsonNode.class);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(expired.getBody().get("code").asText()).isEqualTo("CLAIM_WINDOW_EXPIRED");

        // 勾稽明细可查
        ResponseEntity<JsonNode> view = rest.getForEntity(
                "/api/pension/inheritance-claims/" + claimNo, JsonNode.class);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody().get("ledger").size()).isEqualTo(1);

        // 完成继承支付（终态），重复完成幂等成功
        ResponseEntity<JsonNode> done = rest.postForEntity(
                "/api/pension/inheritance-claims/" + claimNo + "/complete", null, JsonNode.class);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("success").asBoolean()).isTrue();
        assertThat(done.getBody().get("status").asText()).isEqualTo("PAID");
        ResponseEntity<JsonNode> doneAgain = rest.postForEntity(
                "/api/pension/inheritance-claims/" + claimNo + "/complete", null, JsonNode.class);
        assertThat(doneAgain.getBody().get("replayed").asBoolean()).isTrue();

        // 完成后迟到缴费：只记迟到事件并提示人工复核
        rest.exchange("/api/pension/contributions", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "memberNo", "W2",
                        "contributionType", "PERSONAL",
                        "amountCents", 150,
                        "contributionDate", "2026-09-20",
                        "period", "2026-09")), Void.class);
        ResponseEntity<JsonNode> late = rest.getForEntity(
                "/api/pension/members/W2/late-contributions", JsonNode.class);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(late.getBody().size()).isEqualTo(1);
        assertThat(late.getBody().get(0).get("amountCents").asLong()).isEqualTo(150L);
    }
}
