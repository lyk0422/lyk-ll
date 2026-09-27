package com.example.starter.damage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 询价 HTTP 层测试：通过真实 REST 入口验证公开主流程与 400/404/422 错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InquiryApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from risk_event");
        jdbc.update("delete from award_review");
        jdbc.update("delete from bid_opening_entry");
        jdbc.update("delete from bid_opening");
        jdbc.update("delete from quotation_line");
        jdbc.update("delete from quotation");
        jdbc.update("delete from inquiry_item_method");
        jdbc.update("delete from inquiry_item");
        jdbc.update("delete from inquiry");
        jdbc.update("delete from supplier_qual_method");
        jdbc.update("delete from supplier_qualification");
        jdbc.update("delete from supplier");
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
    }

    private String postJson(String path, String body) throws Exception {
        return mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void publishAndBidOverHttp() throws Exception {
        String registered = postJson("/api/cases", """
                {"requestId":"reg-http-1","containerNo":"TEMU7000001","containerValue":10000.00}
                """);
        long caseId = Long.parseLong(registered.replaceAll(".*\"id\":(\\d+).*", "$1"));
        postJson("/api/cases/" + caseId + "/survey", """
                {"requestId":"svy-http-1","items":[
                  {"iiclCode":"DN","component":"侧板","description":"凹痕","responsibility":"OWNER","repairCost":1000.00}]}
                """);
        postJson("/api/cases/" + caseId + "/estimates", """
                {"requestId":"est-http-1"}
                """);
        String detail = mvc.perform(get("/api/cases/{id}", caseId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long itemId = Long.parseLong(detail.replaceAll(".*\"items\":\\[\\{\"id\":(\\d+).*", "$1"));

        String supplier = postJson("/api/suppliers", """
                {"requestId":"sup-http-1","supplierNo":"SUP900","name":"合成修箱厂",
                 "methods":["SEAL"],"validFrom":"2026-01-01T00:00:00","validUntil":"2027-01-01T00:00:00"}
                """);
        long supplierId = Long.parseLong(supplier.replaceAll("^\\{\"id\":(\\d+).*", "$1"));

        String inquiry = mvc.perform(post("/api/cases/{id}/inquiries", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"inq-http-1","items":[{"itemId":%d,"methods":["SEAL"]}],
                                 "deadline":"2099-01-01T00:00:00"}
                                """.formatted(itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.inquiry.version", is(1)))
                .andExpect(jsonPath("$.inquiry.status", is("OPEN")))
                .andReturn().getResponse().getContentAsString();
        long inquiryId = Long.parseLong(inquiry.replaceAll(".*\"inquiry\":\\{\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/inquiries/{id}/quotations", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bid-http-1","supplierId":%d,
                                 "lines":[{"itemId":%d,"method":"SEAL","amountCents":8800}]}
                                """.formatted(supplierId, itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.totalAmount", is(8800)));

        mvc.perform(get("/api/inquiries/{id}", inquiryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotations.length()", is(1)))
                .andExpect(jsonPath("$.opening", nullValue()));
    }

    @Test
    void unknownInquiryReturns404() throws Exception {
        mvc.perform(get("/api/inquiries/{id}", 999999))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPublishParamsReturn400() throws Exception {
        mvc.perform(post("/api/cases/{id}/inquiries", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void incompleteCoverageReturns422() throws Exception {
        String registered = postJson("/api/cases", """
                {"requestId":"reg-http-2","containerNo":"TEMU7000002","containerValue":10000.00}
                """);
        long caseId = Long.parseLong(registered.replaceAll(".*\"id\":(\\d+).*", "$1"));
        postJson("/api/cases/" + caseId + "/survey", """
                {"requestId":"svy-http-2","items":[
                  {"iiclCode":"DN","component":"侧板","description":"凹痕","responsibility":"OWNER","repairCost":1000.00},
                  {"iiclCode":"BR","component":"底梁","description":"弯曲","responsibility":"OWNER","repairCost":500.00}]}
                """);
        postJson("/api/cases/" + caseId + "/estimates", """
                {"requestId":"est-http-2"}
                """);
        String detail = mvc.perform(get("/api/cases/{id}", caseId))
                .andReturn().getResponse().getContentAsString();
        String[] ids = detail.replaceAll(".*\"items\":\\[\\{\"id\":(\\d+),.*?\\{\"id\":(\\d+).*", "$1 $2").split(" ");

        String supplier = postJson("/api/suppliers", """
                {"requestId":"sup-http-2","supplierNo":"SUP901","name":"合成修箱厂乙",
                 "methods":["SEAL"],"validFrom":"2026-01-01T00:00:00","validUntil":"2027-01-01T00:00:00"}
                """);
        long supplierId = Long.parseLong(supplier.replaceAll("^\\{\"id\":(\\d+).*", "$1"));

        String inquiry = postJson("/api/cases/" + caseId + "/inquiries", """
                {"requestId":"inq-http-2","items":[
                  {"itemId":%s,"methods":["SEAL"]},{"itemId":%s,"methods":["SEAL"]}],
                 "deadline":"2099-01-01T00:00:00"}
                """.formatted(ids[0], ids[1]));
        long inquiryId = Long.parseLong(inquiry.replaceAll(".*\"inquiry\":\\{\"id\":(\\d+).*", "$1"));

        // 只覆盖一项，缺一整版拒绝 → 422
        mvc.perform(post("/api/inquiries/{id}/quotations", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bid-http-2","supplierId":%d,
                                 "lines":[{"itemId":%s,"method":"SEAL","amountCents":100}]}
                                """.formatted(supplierId, ids[0])))
                .andExpect(status().isUnprocessableEntity());
    }
}
