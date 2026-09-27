package com.example.starter.damage;

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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密封询价 HTTP 层测试：真实 REST 入口验证主流程与 400/404/409/422 错误语义、
 * requestId 幂等以及结构化指纹（含分隔符文本不破坏结构）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InquiryTestClockConfig.class)
class InquiryApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private final Instant base = Instant.parse("2026-09-25T00:00:00Z");
    private final Instant deadline = base.plus(2, ChronoUnit.DAYS);
    private final Instant afterDeadline = deadline.plus(1, ChronoUnit.HOURS);

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from bid_line");
        jdbc.update("delete from bid_version");
        jdbc.update("delete from bid_review");
        jdbc.update("delete from bid_opening_entry");
        jdbc.update("delete from bid_opening");
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
        clock.set(base);
    }

    private long registeredRepairCase() throws Exception {
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-api","containerNo":"TEMU4000001","containerValue":10000.00}
                                """))
                .andExpect(status().isCreated());
        long caseId = jdbc.queryForObject("select max(id) from damage_case", Long.class);
        mvc.perform(post("/api/cases/" + caseId + "/survey").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"svy-api","items":[
                                  {"iiclCode":"DN","component":"侧板","description":"凹痕","responsibility":"OWNER","repairCost":1000.00},
                                  {"iiclCode":"BR","component":"底梁","description":"弯曲","responsibility":"TERMINAL","repairCost":500.00}]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cases/" + caseId + "/estimates").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"est-api\"}")).andExpect(status().isOk());
        return caseId;
    }

    private long itemId(long caseId, int ordinal) {
        return jdbc.queryForObject(
                "select id from damage_item where case_id = ? order by id limit 1 offset " + (ordinal - 1),
                Long.class, caseId);
    }

    private void createSupplier(String no, String name, String methods) throws Exception {
        mvc.perform(post("/api/suppliers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"csup-%s","supplierNo":"%s","name":"%s","methods":%s}"""
                                .formatted(no, no, name, methods)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/suppliers/{no}/qualifications/grant", no)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"q-%s","expectedVersion":0,
                                 "validFrom":"2026-01-01T00:00:00Z","validTo":null}""".formatted(no)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentQualification.status", is("ACTIVE")))
                .andExpect(jsonPath("$.version", is(1)));
    }

    @Test
    void fullSealedBiddingFlowOverHttpConvertsToApproval() throws Exception {
        long caseId = registeredRepairCase();
        long i1 = itemId(caseId, 1);
        long i2 = itemId(caseId, 2);

        // 发布询价
        String publishBody = """
                {"requestId":"pub-api","expectedVersion":0,"deadline":"%s","items":[
                  {"damageItemId":%d,"methods":["WELD","STRAIGHTEN"]},
                  {"damageItemId":%d,"methods":["WELD","REPLACE"]}]}
                """.formatted(deadline, i1, i2);
        MvcResult pub = mvc.perform(post("/api/cases/{id}/inquiries", caseId)
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.status", is("OPEN")))
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andReturn();
        long inquiryId = jdbc.queryForObject("select max(id) from inquiry", Long.class);

        createSupplier("S001", "供应商1", "[\"WELD\"]");
        createSupplier("S002", "供应商2", "[\"STRAIGHTEN\",\"REPLACE\"]");

        mvc.perform(post("/api/inquiries/{id}/bids/{no}", inquiryId, "S001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b1","expectedVersion":0,"lines":[
                                  {"lineNo":1,"methodCode":"WELD","amountCents":1000},
                                  {"lineNo":2,"methodCode":"WELD","amountCents":2000}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is(1)));
        mvc.perform(post("/api/inquiries/{id}/bids/{no}", inquiryId, "S002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b2","expectedVersion":0,"lines":[
                                  {"lineNo":1,"methodCode":"STRAIGHTEN","amountCents":1500},
                                  {"lineNo":2,"methodCode":"REPLACE","amountCents":1400}]}"""))
                .andExpect(status().isOk());

        // 开标（截止后）
        clock.set(afterDeadline);
        mvc.perform(post("/api/inquiries/{id}/open", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"open-api\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("AWARDED")))
                .andExpect(jsonPath("$.winnerSupplierNo", is("S002")))
                .andExpect(jsonPath("$.winnerTotalCents", is(2900)));

        // 重复开标（新 requestId、新版本号）仍返回首次冻结清单
        mvc.perform(post("/api/inquiries/{id}/open", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"open-api-2\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerSupplierNo", is("S002")));

        // 双重复核
        mvc.perform(post("/api/inquiries/{id}/reviews", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rv1\",\"reviewer\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opening.converted", is(false)));
        mvc.perform(post("/api/inquiries/{id}/reviews", inquiryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rv2\",\"reviewer\":\"bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opening.converted", is(true)))
                .andExpect(jsonPath("$.approvalDate").exists());

        // 原案已产生询价来源批复
        mvc.perform(get("/api/cases/{id}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseInfo.status", is("APPROVED")))
                .andExpect(jsonPath("$.approvals[0].source", is("INQUIRY")))
                .andExpect(jsonPath("$.approvals[0].openingId").exists());
        mvc.perform(get("/api/inquiries/{id}/opening", inquiryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converted", is(true)))
                .andExpect(jsonPath("$.reviewers.length()", is(2)));
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        // 缺少 deadline / items
        mvc.perform(post("/api/cases/1/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"pub-bad\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        // 金额非正整数分
        long caseId = registeredRepairCase();
        long i1 = itemId(caseId, 1);
        long i2 = itemId(caseId, 2);
        // 先发布一个合法询价
        mvc.perform(post("/api/cases/{id}/inquiries", caseId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"pub-ok","expectedVersion":0,"deadline":"%s","items":[
                          {"damageItemId":%d,"methods":["WELD"]},
                          {"damageItemId":%d,"methods":["WELD"]}]}""".formatted(deadline, i1, i2)))
                .andExpect(status().isOk());
        long inquiryId = jdbc.queryForObject("select max(id) from inquiry", Long.class);
        createSupplier("S010", "X", "[\"WELD\"]");
        mvc.perform(post("/api/inquiries/{id}/bids/{no}", inquiryId, "S010")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bid-zero","expectedVersion":0,"lines":[
                                  {"lineNo":1,"methodCode":"WELD","amountCents":0},
                                  {"lineNo":2,"methodCode":"WELD","amountCents":100}]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingObjectsReturn404() throws Exception {
        mvc.perform(get("/api/inquiries/999999"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/suppliers/GHOST"))
                .andExpect(status().isNotFound());
    }

    @Test
    void businessRejectionReturns422() throws Exception {
        // 对未估价案件发布询价 → 422
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-422","containerNo":"TEMU4000002","containerValue":10000.00}"""))
                .andExpect(status().isCreated());
        long caseId = jdbc.queryForObject("select max(id) from damage_case", Long.class);
        // items 形状合法（非空），但案件尚未估价，状态校验先触发 422
        mvc.perform(post("/api/cases/{id}/inquiries", caseId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pub-422","expectedVersion":0,"deadline":"%s","items":[
                                  {"damageItemId":999,"methods":["WELD"]}]}"""
                                .formatted(deadline)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void idempotentReplaySameBodyAndConflictDifferentBody() throws Exception {
        long caseId = registeredRepairCase();
        long i1 = itemId(caseId, 1);
        long i2 = itemId(caseId, 2);
        String body = """
                {"requestId":"pub-idem","expectedVersion":0,"deadline":"%s","items":[
                  {"damageItemId":%d,"methods":["WELD"]},
                  {"damageItemId":%d,"methods":["WELD"]}]}
                """.formatted(deadline, i1, i2);
        mvc.perform(post("/api/cases/{id}/inquiries", caseId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // 同键同参重放
        mvc.perform(post("/api/cases/{id}/inquiries", caseId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // 同键异参（不同截止时刻）→ 409
        String other = body.replace(deadline.toString(), afterDeadline.toString());
        mvc.perform(post("/api/cases/{id}/inquiries", caseId)
                        .contentType(MediaType.APPLICATION_JSON).content(other))
                .andExpect(status().isConflict());
        Integer count = jdbc.queryForObject("select count(*) from inquiry where case_id = ?", Integer.class, caseId);
        assertThatCountIsOne(count);
    }

    private void assertThatCountIsOne(Integer count) {
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    void structuredFingerprintDistinguishesDelimiterAndMergedLists() throws Exception {
        // 名称含管道分隔符与引号，可正常存取与重放
        String pipeName = "a|b\",\"evil";
        mvc.perform(post("/api/suppliers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"csup-pipe","supplierNo":"S020","name":"a|b\\",\\"evil","methods":["WELD"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierNo", is("S020")));
        mvc.perform(get("/api/suppliers/S020"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").exists());

        // 朴素拼接会把 ["A","B"] 与 ["AB"] 都拼成 AB；结构化 JSON 指纹必须区分：
        // 先以 methods=["A","B"]（未知工法但首次成功建占位前，合法 JSON）—首次用合法工法成功，再以同键 ["AB"] 冲突。
        mvc.perform(post("/api/suppliers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fp-key","supplierNo":"S021","name":"n","methods":["WELD","PATCH"]}"""))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/suppliers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fp-key","supplierNo":"S021","name":"n","methods":["WELDPATCH"]}"""))
                .andExpect(status().isConflict());
    }
}
