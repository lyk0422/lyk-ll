package com.example.starter.lc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.lc.testsupport.TestClockConfig;

/**
 * REST 层端到端测试：验证交单→拒付→放弃→付款主流程及 409/422/400 错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class LcControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM discrepancy");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM presentation_batch");
        jdbcTemplate.update("DELETE FROM lc_account");
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-01T10:00:00"));
    }

    @Test
    void fullFlowPresentNoticeWaivePayOverHttp() throws Exception {
        createLc("LC-HTTP", "1000.00");

        // 交单
        mockMvc.perform(post("/api/lc/presentations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"p-1","lcNo":"LC-HTTP","presentationNo":"P-H1",
                                 "drawAmount":300.00,
                                 "docs":[{"docType":"BILL_OF_LADING","docNo":"BL-1","description":"bl"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.deadlineUtc").value("2026-06-06T17:00:00"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 时效内拒付，一次性列明不符点
        TestClockConfig.CLOCK.setDateTime(LocalDateTime.parse("2026-06-06T16:59:59"));
        mockMvc.perform(post("/api/lc/presentations/notice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"n-1","presentationNo":"P-H1",
                                 "discrepancies":[
                                   {"docType":"BILL_OF_LADING","code":"LATE_SHIPMENT","description":"late"},
                                   {"docType":"COMMERCIAL_INVOICE","code":"AMOUNT_MISMATCH","description":"diff"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.discrepancies.length()").value(2));

        // 部分放弃 → 422
        mockMvc.perform(post("/api/lc/presentations/waive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-bad","presentationNo":"P-H1",
                                 "waivedDiscrepancies":[{"docType":"BILL_OF_LADING","code":"LATE_SHIPMENT"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARTIAL_WAIVER"));

        // 全部放弃 → ACCEPTED
        mockMvc.perform(post("/api/lc/presentations/waive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-1","presentationNo":"P-H1",
                                 "waivedDiscrepancies":[
                                   {"docType":"BILL_OF_LADING","code":"LATE_SHIPMENT"},
                                   {"docType":"COMMERCIAL_INVOICE","code":"AMOUNT_MISMATCH"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // 付款 → PAID，额度核减
        mockMvc.perform(post("/api/lc/presentations/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pay-1","presentationNo":"P-H1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(get("/api/lc/accounts/LC-HTTP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountAvailable").value(700.00));

        // 同键重放付款：replayed=true，额度不再变化
        mockMvc.perform(post("/api/lc/presentations/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pay-1","presentationNo":"P-H1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(get("/api/lc/accounts/LC-HTTP"))
                .andExpect(jsonPath("$.amountAvailable").value(700.00));
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        createLc("LC-409", "1000.00");
        String body1 = """
                {"requestId":"same","lcNo":"LC-409","presentationNo":"P-A",
                 "drawAmount":100.00,
                 "docs":[{"docType":"BILL_OF_LADING","docNo":"BL-1"}]}
                """;
        mockMvc.perform(post("/api/lc/presentations")
                .contentType(MediaType.APPLICATION_JSON).content(body1)).andExpect(status().isOk());

        String body2 = """
                {"requestId":"same","lcNo":"LC-409","presentationNo":"P-B",
                 "drawAmount":100.00,
                 "docs":[{"docType":"BILL_OF_LADING","docNo":"BL-1"}]}
                """;
        mockMvc.perform(post("/api/lc/presentations")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        // drawAmount 缺失/为负、docs 为空
        mockMvc.perform(post("/api/lc/presentations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","lcNo":"LC-X","presentationNo":"P-X",
                                 "drawAmount":-1,"docs":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownPresentationReturns404() throws Exception {
        mockMvc.perform(get("/api/lc/presentations/NO-SUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private void createLc(String lcNo, String amount) throws Exception {
        String body = """
                {"lcNo":"%s","applicant":"APL","beneficiary":"BEN","currency":"USD",
                 "amountTotal":%s,"expiryUtc":"2027-12-31T00:00:00"}
                """.formatted(lcNo, amount);
        mockMvc.perform(post("/api/lc/accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(true).isTrue();
    }
}
