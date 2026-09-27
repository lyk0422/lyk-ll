package com.example.starter.damage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密封报价 HTTP 层测试：真实 REST 入口下的主流程前半段、400/404/409/422 语义与结构化指纹。
 * 使用系统 UTC 时钟，截止时刻取远期，保证投标受理与“未到截止 422”稳定。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SealedBidApiTest {

    /** 远期 UTC 截止时刻，真实时钟下始终尚未截止 */
    private static final String FAR_DEADLINE = "2030-01-01T00:00:00";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbc.update("delete from bid_item");
        jdbc.update("delete from bid_exclusion");
        jdbc.update("delete from bid_review");
        jdbc.update("delete from bid_opening");
        jdbc.update("delete from bid");
        jdbc.update("delete from inquiry_item_method");
        jdbc.update("delete from inquiry_item");
        jdbc.update("delete from inquiry");
        jdbc.update("delete from qualification_method");
        jdbc.update("delete from supplier_qualification");
        jdbc.update("delete from case_event");
        jdbc.update("delete from reinspection");
        jdbc.update("delete from repair_completion");
        jdbc.update("delete from repair_approval");
        jdbc.update("delete from estimate");
        jdbc.update("delete from damage_item");
        jdbc.update("delete from damage_case");
        jdbc.update("delete from idempotency_record");
        // 共享内存库下重置合成供应商资格为种子版本，避免其他测试类遗留撤销/续期状态
        jdbc.update("insert into supplier_qualification(supplier_id, version, valid_from, valid_until,"
                        + " status, request_id, created_at)"
                        + " select id, 1, '2026-01-01 00:00:00', '2026-12-31 23:59:59', 'ACTIVE', 'seed',"
                        + " '2026-01-01 00:00:00' from supplier");
        jdbc.update("insert into qualification_method(qualification_id, method_code)"
                + " select q.id, m.code from supplier_qualification q join supplier s on s.id = q.supplier_id"
                + " cross join (select 'HM' code union all select 'WD' union all select 'SP') m"
                + " where s.supplier_no = 'SUP-100'");
        jdbc.update("insert into qualification_method(qualification_id, method_code)"
                + " select q.id, m.code from supplier_qualification q join supplier s on s.id = q.supplier_id"
                + " cross join (select 'WD' code union all select 'RP') m"
                + " where s.supplier_no = 'SUP-200'");
        jdbc.update("insert into qualification_method(qualification_id, method_code)"
                + " select q.id, m.code from supplier_qualification q join supplier s on s.id = q.supplier_id"
                + " cross join (select 'HM' code union all select 'PT' union all select 'SP') m"
                + " where s.supplier_no = 'SUP-300'");
    }

    private record PreparedCase(long caseId, long item1, long item2) {
    }

    private PreparedCase prepareRepairCase() throws Exception {
        String registered = mvc.perform(post("/api/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-sealed-1","containerNo":"TEMU4000001","containerValue":10000.00}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long caseId = Long.parseLong(registered.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/cases/{id}/survey", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"svy-sealed-1","items":[
                                  {"iiclCode":"DN","component":"侧板","description":"凹痕","responsibility":"OWNER","repairCost":1000.00},
                                  {"iiclCode":"BR","component":"底梁","description":"弯曲","responsibility":"TERMINAL","repairCost":500.00}]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/cases/{id}/estimates", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"est-sealed-1\"}"))
                .andExpect(status().isOk());
        String detail = mvc.perform(get("/api/cases/{id}", caseId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode items = objectMapper.readTree(detail).get("items");
        return new PreparedCase(caseId, items.get(0).get("id").asLong(), items.get(1).get("id").asLong());
    }

    private String publishBody(String requestId, long item1, long item2, String methods2) {
        return """
                {"requestId":"%s","expectedVersion":1,"deadline":"%s","items":[
                  {"itemId":%d,"allowedMethods":["HM","WD"]},
                  {"itemId":%d,"allowedMethods":[%s]}]}
                """.formatted(requestId, FAR_DEADLINE, item1, item2, methods2);
    }

    @Test
    void publishBidAndOpenGuardsOverHttp() throws Exception {
        PreparedCase pc = prepareRepairCase();

        mvc.perform(post("/api/cases/{id}/inquiries", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("pub-http-1", pc.item1(), pc.item2(), "\"RP\",\"WD\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.items.length()", is(2)));

        mvc.perform(post("/api/cases/{id}/inquiries/1/bids", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bid-http-200","supplierNo":"SUP-200","expectedVersion":1,"lines":[
                                  {"itemSeq":1,"methodCode":"WD","priceCents":100000},
                                  {"itemSeq":2,"methodCode":"RP","priceCents":80000}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision", is(1)))
                .andExpect(jsonPath("$.totalCents", is(180000)));

        // 远期截止：当前时刻开标被业务拒绝 422
        mvc.perform(post("/api/cases/{id}/inquiries/1/open", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"open-too-early\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 未开标查询 404
        mvc.perform(get("/api/cases/{id}/inquiries/1/opening", pc.caseId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishValidationErrorReturns400() throws Exception {
        PreparedCase pc = prepareRepairCase();
        // 缺 expectedVersion 与 deadline，items 为空
        mvc.perform(post("/api/cases/{id}/inquiries", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"pub-bad\",\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishOnUnknownCaseReturns404() {
        // 残损案不存在：404 优先于报文校验
        try {
            mvc.perform(post("/api/cases/999999/inquiries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"pub-404","expectedVersion":1,
                                     "deadline":"2030-01-01T00:00:00","items":[
                                      {"itemId":1,"allowedMethods":["HM"]}]}
                                    """))
                    .andExpect(status().isNotFound());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void bidWithUnknownSupplierReturns404() throws Exception {
        PreparedCase pc = prepareRepairCase();
        mvc.perform(post("/api/cases/{id}/inquiries", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("pub-404b", pc.item1(), pc.item2(), "\"SP\"")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/cases/{id}/inquiries/1/bids", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bid-404","supplierNo":"NOPE","expectedVersion":1,"lines":[
                                  {"itemSeq":1,"methodCode":"HM","priceCents":100},
                                  {"itemSeq":2,"methodCode":"SP","priceCents":100}]}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        PreparedCase pc = prepareRepairCase();
        String body = publishBody("pub-http-idem", pc.item1(), pc.item2(), "\"SP\"");
        mvc.perform(post("/api/cases/{id}/inquiries", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // 同键同参重放
        mvc.perform(post("/api/cases/{id}/inquiries", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // 同键异参（第 2 项允许工法集合不同）→ 409
        mvc.perform(post("/api/cases/{id}/inquiries", pc.caseId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("pub-http-idem", pc.item1(), pc.item2(), "\"HM\"")))
                .andExpect(status().isConflict());
    }
}
