package com.example.starter.damage;

import com.example.starter.damage.support.MutableUtcClock;
import com.example.starter.damage.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密封询价 HTTP 层测试：通过真实 REST 入口验证主流程、400/404/409/422 错误语义与查询接口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class SealedInquiryApiTest {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 10, 1, 12, 0, 0);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableUtcClock clock;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from repair_award");
        jdbc.update("delete from bid_opening_line");
        jdbc.update("delete from bid_opening");
        jdbc.update("delete from bid_item");
        jdbc.update("delete from bid");
        jdbc.update("delete from inquiry_item_method");
        jdbc.update("delete from inquiry_item");
        jdbc.update("delete from inquiry");
        jdbc.update("delete from supplier_qualification");
        jdbc.update("delete from supplier_method");
        jdbc.update("delete from supplier");
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
        clock.setTime(LocalDateTime.of(2026, 10, 1, 0, 0, 0));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private long repairCase(String no) throws Exception {
        MvcResult registered = mvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"reg-%s","containerNo":"%s","containerValue":10000.00}
                                """.formatted(no, no)))
                .andExpect(status().isCreated()).andReturn();
        long caseId = body(registered).get("id").asLong();
        mvc.perform(post("/api/cases/{id}/survey", caseId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"svy-%s","items":[
                                  {"iiclCode":"DN","component":"侧板","description":"凹痕","responsibility":"OWNER","repairCost":100.00},
                                  {"iiclCode":"BR","component":"底梁","description":"弯曲","responsibility":"TERMINAL","repairCost":200.00}]}
                                """.formatted(no)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cases/{id}/estimates", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"est-" + no + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.determination", is("REPAIR")));
        return caseId;
    }

    private void createSupplier(String code) throws Exception {
        mvc.perform(post("/api/suppliers").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"sup-%s","supplierCode":"%s","name":"供应商%s",
                 "methods":["WELD","PATCH"],
                 "validFrom":"2026-09-01T00:00:00","validTo":"2026-10-11T12:00:00"}
                """.formatted(code, code, code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.currentQualification.version", is(1)));
    }

    /** 发布标准两项询价，返回 [inquiryId, inquiryItemId1, inquiryItemId2] */
    private long[] publishInquiry(long caseId, String requestId, int expectedVersion) throws Exception {
        JsonNode detail = body(mvc.perform(get("/api/cases/{id}", caseId)).andExpect(status().isOk()).andReturn());
        long item1 = detail.get("items").get(0).get("id").asLong();
        long item2 = detail.get("items").get(1).get("id").asLong();
        MvcResult result = mvc.perform(post("/api/cases/{id}/inquiries", caseId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","expectedVersion":%d,"deadline":"2026-10-01T12:00:00","items":[
                                  {"damageItemId":%d,"allowedMethods":["WELD"]},
                                  {"damageItemId":%d,"allowedMethods":["PATCH"]}]}
                                """.formatted(requestId, expectedVersion, item1, item2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(expectedVersion + 1)))
                .andReturn();
        JsonNode inquiry = body(result);
        return new long[]{inquiry.get("id").asLong(),
                inquiry.get("items").get(0).get("id").asLong(),
                inquiry.get("items").get(1).get("id").asLong()};
    }

    private void submitBid(long inquiryId, long inqItem1, long inqItem2, String code, String requestId,
                           int expectedVersion, long amount1, long amount2) throws Exception {
        mvc.perform(post("/api/inquiries/{id}/bids", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","supplierCode":"%s","expectedVersion":%d,"items":[
                                  {"inquiryItemId":%d,"methodCode":"WELD","amountCents":%d},
                                  {"inquiryItemId":%d,"methodCode":"PATCH","amountCents":%d}]}
                                """.formatted(requestId, code, expectedVersion, inqItem1, amount1,
                                inqItem2, amount2)))
                .andExpect(status().isOk());
    }

    @Test
    void fullSealedInquiryFlowOverHttp() throws Exception {
        long caseId = repairCase("TEMU3300001");
        createSupplier("SUP-A");
        createSupplier("SUP-B");
        long[] inq = publishInquiry(caseId, "inq-1", 0);
        long inquiryId = inq[0];

        mvc.perform(post("/api/inquiries/{id}/bids", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"bid-a","supplierCode":"SUP-A","expectedVersion":0,"items":[
                                  {"inquiryItemId":%d,"methodCode":"WELD","amountCents":1000},
                                  {"inquiryItemId":%d,"methodCode":"PATCH","amountCents":2000}]}
                                """.formatted(inq[1], inq[2])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision", is(1)))
                .andExpect(jsonPath("$.totalAmountCents", is(3000)));
        submitBid(inquiryId, inq[1], inq[2], "SUP-B", "bid-b", 0, 1500, 1400);

        // 未到截止开标 422
        mvc.perform(post("/api/inquiries/{id}/openings", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"open-early\"}"))
                .andExpect(status().isUnprocessableEntity());

        // 越过截止时刻后开标
        clock.setTime(DEADLINE.plusHours(1));
        mvc.perform(post("/api/inquiries/{id}/openings", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"open-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerSupplierCode", is("SUP-B")))
                .andExpect(jsonPath("$.winnerTotalAmountCents", is(2900)))
                .andExpect(jsonPath("$.lines.length()", is(2)));

        // 开标清单查询
        mvc.perform(get("/api/inquiries/{id}/openings", inquiryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerSupplierCode", is("SUP-B")));

        // 同一复核人 422
        mvc.perform(post("/api/inquiries/{id}/award-confirmations", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"award-x","expectedVersion":1,"reviewer1":"alice","reviewer2":"alice",
                                 "approvalDate":"2026-10-02"}"""))
                .andExpect(status().isUnprocessableEntity());

        // 双人不同复核转原案批复
        mvc.perform(post("/api/inquiries/{id}/award-confirmations", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"award-1","expectedVersion":1,"reviewer1":"alice","reviewer2":"bob",
                                 "approvalDate":"2026-10-02"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierCode", is("SUP-B")))
                .andExpect(jsonPath("$.qualificationVersion", is(1)));

        // 原案已批复、询价冻结、中标凭证可查
        mvc.perform(get("/api/cases/{id}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseInfo.status", is("APPROVED")))
                .andExpect(jsonPath("$.approvals.length()", is(1)));
        mvc.perform(get("/api/inquiries/{id}", inquiryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("FROZEN")));
        mvc.perform(get("/api/cases/{id}/award", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewer1", is("alice")))
                .andExpect(jsonPath("$.reviewer2", is("bob")));
    }

    @Test
    void validationAndErrorSemanticsOverHttp() throws Exception {
        long caseId = repairCase("TEMU3300002");
        createSupplier("SUP-A");

        // 供应商字段缺失 400
        mvc.perform(post("/api/suppliers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"sup-bad\",\"supplierCode\":\"SUP-X\"}"))
                .andExpect(status().isBadRequest());

        // 未知对象 404
        mvc.perform(get("/api/suppliers/NOBODY")).andExpect(status().isNotFound());
        mvc.perform(get("/api/inquiries/999999")).andExpect(status().isNotFound());

        // 全损案件发布询价 422
        MvcResult lossReg = mvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"reg-loss","containerNo":"TEMU3300099","containerValue":10000.00}"""))
                .andExpect(status().isCreated()).andReturn();
        long lossCase = body(lossReg).get("id").asLong();
        mvc.perform(post("/api/cases/{id}/survey", lossCase)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"svy-loss","items":[
                                  {"iiclCode":"DN","component":"侧板","description":"凹痕","responsibility":"OWNER","repairCost":3000.01}]}"""))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cases/{id}/estimates", lossCase)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"est-loss\"}"))
                .andExpect(status().isOk());
        long lossItem = body(mvc.perform(get("/api/cases/{id}", lossCase)).andReturn())
                .get("items").get(0).get("id").asLong();
        mvc.perform(post("/api/cases/{id}/inquiries", lossCase)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"inq-loss","expectedVersion":0,"deadline":"2026-10-01T12:00:00",
                                 "items":[{"damageItemId":%d,"allowedMethods":["WELD"]}]}""".formatted(lossItem)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("待批复")));

        // 发布合法询价
        long[] inq = publishInquiry(caseId, "inq-1", 0);
        long inquiryId = inq[0];

        // 金额非正整数 400（Bean Validation 拦截，整版不受理）
        mvc.perform(post("/api/inquiries/{id}/bids", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"bid-zero","supplierCode":"SUP-A","expectedVersion":0,"items":[
                                  {"inquiryItemId":%d,"methodCode":"WELD","amountCents":0},
                                  {"inquiryItemId":%d,"methodCode":"PATCH","amountCents":2000}]}
                                """.formatted(inq[1], inq[2])))
                .andExpect(status().isBadRequest());

        // 缺项整版拒绝 422
        mvc.perform(post("/api/inquiries/{id}/bids", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"bid-miss","supplierCode":"SUP-A","expectedVersion":0,"items":[
                                  {"inquiryItemId":%d,"methodCode":"WELD","amountCents":1000}]}
                                """.formatted(inq[1])))
                .andExpect(status().isUnprocessableEntity());

        // 合法报价后，同键异参 409、同键同参重放首次结果
        submitBid(inquiryId, inq[1], inq[2], "SUP-A", "bid-ok", 0, 1000, 2000);
        mvc.perform(post("/api/inquiries/{id}/bids", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"bid-ok","supplierCode":"SUP-A","expectedVersion":0,"items":[
                                  {"inquiryItemId":%d,"methodCode":"WELD","amountCents":1001},
                                  {"inquiryItemId":%d,"methodCode":"PATCH","amountCents":2000}]}
                                """.formatted(inq[1], inq[2])))
                .andExpect(status().isConflict());

        // 恰在截止时刻投标 422
        clock.setTime(DEADLINE);
        mvc.perform(post("/api/inquiries/{id}/bids", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"bid-exact","supplierCode":"SUP-A","expectedVersion":1,"items":[
                                  {"inquiryItemId":%d,"methodCode":"WELD","amountCents":1},
                                  {"inquiryItemId":%d,"methodCode":"PATCH","amountCents":1}]}
                                """.formatted(inq[1], inq[2])))
                .andExpect(status().isUnprocessableEntity());

        // 已发布询价的案件旧批复入口 422
        mvc.perform(post("/api/cases/{id}/approvals", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"legacy-ap\",\"approvalDate\":\"2026-10-01\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void legacyApprovalStillWorksForCaseWithoutInquiry() throws Exception {
        // 无询价旧案：旧批复入口保持兼容
        long caseId = repairCase("TEMU3300003");
        mvc.perform(post("/api/cases/{id}/approvals", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"legacy-ap\",\"approvalDate\":\"2026-10-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }
}
