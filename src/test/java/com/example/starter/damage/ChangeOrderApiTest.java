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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修理变更单 HTTP 层测试：真实 REST 入口验证候选创建（201）、查询前后对照视图、
 * 双重复核生效换版、撤回以及 400/404/409/422 错误语义与 requestId 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(InquiryTestClockConfig.class)
class ChangeOrderApiTest {

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
        jdbc.update("delete from change_order_review");
        jdbc.update("delete from change_order_line");
        jdbc.update("delete from change_order");
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

    private long queryLong(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    /** 经 REST 构造 APPROVED 案件（密封询价转批复），返回案件 ID */
    private long approvedCase() throws Exception {
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-api","containerNo":"TEMU8000001","containerValue":10000.00}
                                """))
                .andExpect(status().isCreated());
        long caseId = queryLong("select max(id) from damage_case");
        mvc.perform(post("/api/cases/" + caseId + "/survey").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"svy-api","items":[
                                  {"iiclCode":"DN","component":"侧板","description":"凹痕","responsibility":"OWNER","repairCost":1000.00},
                                  {"iiclCode":"BR","component":"底梁","description":"弯曲","responsibility":"TERMINAL","repairCost":500.00}]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cases/" + caseId + "/estimates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"est-api\"}"))
                .andExpect(status().isOk());
        long item1 = queryLong("select min(id) from damage_item where case_id = " + caseId);
        long item2 = queryLong("select max(id) from damage_item where case_id = " + caseId);
        mvc.perform(post("/api/cases/" + caseId + "/inquiries").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"pub-api","expectedVersion":0,"deadline":"%s","items":[
                                  {"damageItemId":%d,"methods":["WELD","PATCH"]},
                                  {"damageItemId":%d,"methods":["WELD","REPLACE"]}]}
                                """.formatted(deadline, item1, item2)))
                .andExpect(status().isOk());
        long inquiryId = queryLong("select max(id) from inquiry");
        mvc.perform(post("/api/suppliers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"csup-api","supplierNo":"S-API","name":"API中标供应商",
                                 "methods":["WELD","REPLACE","PATCH"]}
                                """))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/suppliers/S-API/qualifications/grant").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"q-api","expectedVersion":0,"validFrom":"%s"}
                                """.formatted(base.minus(30, ChronoUnit.DAYS))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inquiries/" + inquiryId + "/bids/S-API")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bid-api","expectedVersion":0,"lines":[
                                  {"lineNo":1,"methodCode":"WELD","amountCents":100000},
                                  {"lineNo":2,"methodCode":"REPLACE","amountCents":50000}]}
                                """))
                .andExpect(status().isOk());
        clock.set(afterDeadline);
        mvc.perform(post("/api/inquiries/" + inquiryId + "/open").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"open-api\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inquiries/" + inquiryId + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rv-api-1\",\"reviewer\":\"alice\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/inquiries/" + inquiryId + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"rv-api-2\",\"reviewer\":\"bob\"}"))
                .andExpect(status().isOk());
        return caseId;
    }

    @Test
    void changeOrderRestFlowCreateReviewEffectiveAndComplete() throws Exception {
        long caseId = approvedCase();
        long item1 = queryLong("select min(id) from damage_item where case_id = " + caseId);

        // 创建候选：201，视图展示每项前后工法与金额及前后总额
        MvcResult created = mvc.perform(post("/api/cases/" + caseId + "/change-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"co-api-1","expectedVersion":0,"lines":[
                                  {"damageItemId":%d,"methodCode":"PATCH","amountCents":120000}]}
                                """.formatted(item1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.supplierNo", is("S-API")))
                .andExpect(jsonPath("$.oldTotalCents", is(150000)))
                .andExpect(jsonPath("$.newTotalCents", is(170000)))
                .andExpect(jsonPath("$.lines[0].oldMethodCode", is("WELD")))
                .andExpect(jsonPath("$.lines[0].oldAmountCents", is(100000)))
                .andExpect(jsonPath("$.lines[0].newMethodCode", is("PATCH")))
                .andExpect(jsonPath("$.lines[0].newAmountCents", is(120000)))
                .andExpect(jsonPath("$.lines[1].changed", is(false)))
                .andReturn();
        long orderId = jdbc.queryForObject("select max(id) from change_order", Long.class);

        // 同键同参重放返回首次结果，不新增
        mvc.perform(post("/api/cases/" + caseId + "/change-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"co-api-1","expectedVersion":0,"lines":[
                                  {"damageItemId":%d,"methodCode":"PATCH","amountCents":120000}]}
                                """.formatted(item1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is((int) orderId)));
        // 同键异参 → 409
        mvc.perform(post("/api/cases/" + caseId + "/change-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"co-api-1","expectedVersion":0,"lines":[
                                  {"damageItemId":%d,"methodCode":"PATCH","amountCents":130000}]}
                                """.formatted(item1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));

        // 候选期间完工 → 409
        mvc.perform(post("/api/cases/" + caseId + "/repair-completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"cmp-blocked\",\"completionDate\":\"2026-09-27\"}"))
                .andExpect(status().isConflict());

        // 第一人不切换，第二人生效
        mvc.perform(post("/api/change-orders/" + orderId + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"corv-1","expectedVersion":1,"reviewer":"carol","decision":"AGREE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.newApprovalId", nullValue()));
        mvc.perform(post("/api/change-orders/" + orderId + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"corv-2","expectedVersion":1,"reviewer":"dave","decision":"AGREE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("EFFECTIVE")))
                .andExpect(jsonPath("$.version", is(2)));

        // 案件下变更单列表可查，前后对照完整
        mvc.perform(get("/api/cases/" + caseId + "/change-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status", is("EFFECTIVE")))
                .andExpect(jsonPath("$[0].oldTotalCents", is(150000)))
                .andExpect(jsonPath("$[0].newTotalCents", is(170000)));

        // 生效后按新批复完工验收
        mvc.perform(post("/api/cases/" + caseId + "/repair-completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"cmp-api\",\"completionDate\":\"2026-09-28\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cases/" + caseId + "/reinspections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"ri-api\",\"passed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REPAIR_PASSED")));
    }

    @Test
    void changeOrderRestErrorSemanticsAndWithdraw() throws Exception {
        long caseId = approvedCase();
        long item1 = queryLong("select min(id) from damage_item where case_id = " + caseId);

        // 未知工法 → 400
        mvc.perform(post("/api/cases/" + caseId + "/change-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"co-bad-m","expectedVersion":0,"lines":[
                                  {"damageItemId":%d,"methodCode":"NO_SUCH","amountCents":1000}]}
                                """.formatted(item1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)));
        // 参数缺失（空 lines）→ 400
        mvc.perform(post("/api/cases/" + caseId + "/change-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"co-empty\",\"expectedVersion\":0,\"lines\":[]}"))
                .andExpect(status().isBadRequest());
        // 失败不占键：同键修正后成功
        mvc.perform(post("/api/cases/" + caseId + "/change-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"co-bad-m","expectedVersion":0,"lines":[
                                  {"damageItemId":%d,"methodCode":"PATCH","amountCents":110000}]}
                                """.formatted(item1)))
                .andExpect(status().isCreated());
        long orderId = jdbc.queryForObject("select max(id) from change_order", Long.class);

        // 撤回版本错位 → 409；未知变更单 → 404
        mvc.perform(post("/api/change-orders/" + orderId + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wd-bad\",\"expectedVersion\":9}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/change-orders/999999/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wd-404\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/change-orders/999999"))
                .andExpect(status().isNotFound());

        // 正常撤回 → 200，完工阻断解除
        mvc.perform(post("/api/change-orders/" + orderId + "/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wd-ok\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WITHDRAWN")))
                .andExpect(jsonPath("$.version", is(2)));
        mvc.perform(post("/api/cases/" + caseId + "/repair-completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"cmp-after\",\"completionDate\":\"2026-09-27\"}"))
                .andExpect(status().isOk());
    }
}
