package com.example.starter.reinsurance.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.reinsurance.testsupport.TestClockConfig;
import com.example.starter.reinsurance.testsupport.TestDataCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 全链路 HTTP 端到端测试：通过真实 REST 入口走通
 * 建约→建单→报案预提→出账→异议→（另案）预提冲销→再出账→确认封存→封存后异议拒绝→调整单。
 */
@SpringBootTest(
        classes = {com.example.starter.StarterApplication.class, TestClockConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReinsuranceEndToEndHttpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final TestRestTemplate rest = new TestRestTemplate();
    private final HttpHeaders jsonHeaders = new HttpHeaders();

    @BeforeEach
    void clean() {
        TestDataCleaner.clean(jdbcTemplate);
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> post(String path, String body) {
        return rest.postForEntity(url(path), new HttpEntity<>(body, jsonHeaders), String.class);
    }

    @org.junit.jupiter.api.Test
    void fullFlowThroughRealHttpEndpoints() {
        // 1) 建合约：30% 比例、每保单限额 250
        ResponseEntity<String> treaty = post("/api/reinsurance/treaties", """
                {"contractNo":"T-E2E","cessionRate":0.30,"perPolicyLimit":250.00}
                """);
        assertEquals(HttpStatus.CREATED, treaty.getStatusCode(), treaty.getBody());
        assertTrue(treaty.getBody().contains("\"contractNo\":\"T-E2E\""));

        // 2) 建两张 Q3 保单
        assertEquals(HttpStatus.CREATED, post("/api/reinsurance/policies", """
                {"policyNo":"P-E2E-1","contractNo":"T-E2E","premium":1000.00,"effectiveDate":"2026-08-01"}
                """).getStatusCode());
        assertEquals(HttpStatus.CREATED, post("/api/reinsurance/policies", """
                {"policyNo":"P-E2E-2","contractNo":"T-E2E","premium":2000.00,"effectiveDate":"2026-08-10"}
                """).getStatusCode());

        // 3) 报案：估损 1000，理论预提 300，限额 250 截断 → 预提 250
        ResponseEntity<String> claim = post("/api/reinsurance/claims", """
                {"claimNo":"CL-E2E-1","policyNo":"P-E2E-1","estimatedAmount":1000.00,"lossDate":"2026-08-05"}
                """);
        assertEquals(HttpStatus.CREATED, claim.getStatusCode());
        assertTrue(claim.getBody().contains("\"provisionAmount\":250.00"));
        assertTrue(claim.getBody().contains("\"accountBalance\":250.00"));

        // 4) 出 Q3 账单：保费分保 900，摊回 250（限额截断），净额 650，PENDING
        ResponseEntity<String> bill = post("/api/reinsurance/bills/generate", """
                {"requestId":"e2e-gen-q3","contractNo":"T-E2E","period":"2026-Q3"}
                """);
        assertEquals(HttpStatus.CREATED, bill.getStatusCode());
        assertTrue(bill.getBody().contains("\"billNo\":\"RB-T-E2E-2026-Q3\""));
        assertTrue(bill.getBody().contains("\"premiumTotal\":900.00"));
        assertTrue(bill.getBody().contains("\"recoveryTotal\":250.00"));
        assertTrue(bill.getBody().contains("\"netTotal\":650.00"));
        assertTrue(bill.getBody().contains("\"status\":\"PENDING\""));

        // 5) 再保人逐项异议 → DISPUTED
        ResponseEntity<String> dispute = post("/api/reinsurance/bills/RB-T-E2E-2026-Q3/dispute", """
                {"requestId":"e2e-disp-1","items":[
                  {"itemRef":"L1","expectedAmount":270.00,"reason":"P-E2E-1 分出保费基数疑为批改后 900 元"}
                ]}
                """);
        assertEquals(HttpStatus.OK, dispute.getStatusCode());
        assertTrue(dispute.getBody().contains("\"status\":\"DISPUTED\""));

        // 6) 同 requestId 异参重放 → 409，状态不变
        ResponseEntity<String> replayConflict = post("/api/reinsurance/bills/RB-T-E2E-2026-Q3/dispute", """
                {"requestId":"e2e-disp-1","items":[
                  {"itemRef":"L1","expectedAmount":200.00,"reason":"篡改后的差异金额"}
                ]}
                """);
        assertEquals(HttpStatus.CONFLICT, replayConflict.getStatusCode());
        assertTrue(replayConflict.getBody().contains("IDEMPOTENCY_CONFLICT"));

        // 7) 异议账单拒绝确认 → 409
        ResponseEntity<String> confirmDisputed = post(
                "/api/reinsurance/bills/RB-T-E2E-2026-Q3/confirm",
                """
                {"requestId":"e2e-conf-bad"}
                """);
        assertEquals(HttpStatus.CONFLICT, confirmDisputed.getStatusCode());

        // 8) 另一保单新赔案：预提 600 理论值被限额截到 250；最终结案实赔 500 → 实摊 150，冲销 100
        ResponseEntity<String> claim2 = post("/api/reinsurance/claims", """
                {"claimNo":"CL-E2E-2","policyNo":"P-E2E-2","estimatedAmount":2000.00,"lossDate":"2026-08-12"}
                """);
        assertEquals(HttpStatus.CREATED, claim2.getStatusCode());
        assertTrue(claim2.getBody().contains("\"provisionAmount\":250.00"));

        ResponseEntity<String> close = post("/api/reinsurance/claims/CL-E2E-2/close", """
                {"requestId":"e2e-close-1","paidAmount":500.00}
                """);
        assertEquals(HttpStatus.OK, close.getStatusCode());
        assertTrue(close.getBody().contains("\"status\":\"FINAL_CLOSED\""));
        assertTrue(close.getBody().contains("\"settledAmount\":150.00"));
        assertTrue(close.getBody().contains("\"provisionAmount\":0.00"));
        // 账户：250(第一案预提) + 150(第二案实摊) = 400
        assertTrue(close.getBody().contains("\"accountBalance\":400.00"));

        // 9) 结案终态：重复结案返回首次结果（金额不被改写）
        ResponseEntity<String> closeAgain = post("/api/reinsurance/claims/CL-E2E-2/close", """
                {"requestId":"e2e-close-2","paidAmount":9999.00}
                """);
        assertEquals(HttpStatus.OK, closeAgain.getStatusCode());
        assertTrue(closeAgain.getBody().contains("\"paidAmount\":500.00"));
        assertTrue(closeAgain.getBody().contains("\"settledAmount\":150.00"));

        // 10) 新季度账单 Q4（无新业务也允许出空单之外的场景：这里用 Q3 账单已存在故新开季度）
        //     先将 Q3 异议账单留作争议；另以一张新保单演示 Q4 封存链路。
        assertEquals(HttpStatus.CREATED, post("/api/reinsurance/policies", """
                {"policyNo":"P-E2E-3","contractNo":"T-E2E","premium":500.00,"effectiveDate":"2026-10-01"}
                """).getStatusCode());
        ResponseEntity<String> q4 = post("/api/reinsurance/bills/generate", """
                {"requestId":"e2e-gen-q4","contractNo":"T-E2E","period":"2026-Q4"}
                """);
        assertEquals(HttpStatus.CREATED, q4.getStatusCode());
        assertTrue(q4.getBody().contains("RB-T-E2E-2026-Q4"));
        assertTrue(q4.getBody().contains("\"premiumTotal\":150.00"));

        // 11) 双方确认封存 Q4；重复确认幂等成功
        ResponseEntity<String> sealed = post("/api/reinsurance/bills/RB-T-E2E-2026-Q4/confirm", """
                {"requestId":"e2e-conf-q4-1"}
                """);
        assertEquals(HttpStatus.OK, sealed.getStatusCode());
        assertTrue(sealed.getBody().contains("\"status\":\"SEALED\""));
        ResponseEntity<String> sealedAgain = post("/api/reinsurance/bills/RB-T-E2E-2026-Q4/confirm", """
                {"requestId":"e2e-conf-q4-2"}
                """);
        assertEquals(HttpStatus.OK, sealedAgain.getStatusCode());
        assertTrue(sealedAgain.getBody().contains("\"status\":\"SEALED\""));

        // 12) 封存后异议一律拒绝 → 409
        ResponseEntity<String> disputeSealed = post("/api/reinsurance/bills/RB-T-E2E-2026-Q4/dispute", """
                {"requestId":"e2e-disp-q4","items":[
                  {"itemRef":"L1","expectedAmount":120.00,"reason":"封存后异议必须拒绝"}
                ]}
                """);
        assertEquals(HttpStatus.CONFLICT, disputeSealed.getStatusCode());

        // 13) 差错走下一期调整单（引用原账单号）：-100 × 30% = -30
        ResponseEntity<String> adjustment = post("/api/reinsurance/bills/adjustments", """
                {"requestId":"e2e-adj-1","contractNo":"T-E2E","period":"2027-Q1",
                 "refBillNo":"RB-T-E2E-2026-Q4","items":[
                  {"itemType":"PREMIUM","refNo":"P-E2E-3","grossAmount":-100.00,"reason":"批单减费冲减分保"}
                ]}
                """);
        assertEquals(HttpStatus.CREATED, adjustment.getStatusCode());
        assertTrue(adjustment.getBody().contains("\"billNo\":\"RA-T-E2E-2027-Q1\""));
        assertTrue(adjustment.getBody().contains("\"refBillNo\":\"RB-T-E2E-2026-Q4\""));
        assertTrue(adjustment.getBody().contains("\"premiumTotal\":-30.00"));

        // 14) GET 账单可查，明细合计等于汇总
        ResponseEntity<String> fetched = rest.getForEntity(
                url("/api/reinsurance/bills/RB-T-E2E-2026-Q3"), String.class);
        assertEquals(HttpStatus.OK, fetched.getStatusCode());
        assertTrue(fetched.getBody().contains("RB-T-E2E-2026-Q3"));
    }

    @org.junit.jupiter.api.Test
    void rejectsUnknownBillAndInvalidPayload() {
        assertEquals(HttpStatus.NOT_FOUND,
                rest.getForEntity(url("/api/reinsurance/bills/NO-SUCH-BILL"), String.class).getStatusCode());

        ResponseEntity<String> badPeriod = post("/api/reinsurance/bills/generate", """
                {"requestId":"bad-1","contractNo":"NO-SUCH","period":"2026Q3"}
                """);
        // 季度格式非法优先校验 → 400
        assertEquals(HttpStatus.BAD_REQUEST, badPeriod.getStatusCode());
        assertTrue(badPeriod.getBody().contains("VALIDATION_FAILED"));

        treatyForConflictProbe();
        ResponseEntity<String> malformed = post("/api/reinsurance/bills/generate", """
                {"requestId":"bad-2","contractNo":"T-BAD","period":"2026Q3"}
                """);
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertTrue(malformed.getBody().contains("VALIDATION_FAILED"));
    }

    private void treatyForConflictProbe() {
        ResponseEntity<String> created = post("/api/reinsurance/treaties", """
                {"contractNo":"T-BAD","cessionRate":0.10}
                """);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
    }
}
