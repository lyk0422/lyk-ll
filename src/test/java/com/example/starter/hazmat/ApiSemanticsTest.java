package com.example.starter.hazmat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.hazmat.support.HazmatIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 层错误语义测试：200 主流程、400 参数非法、404 不存在、409 幂等冲突、422 业务规则。
 */
@AutoConfigureMockMvc
class ApiSemanticsTest extends HazmatIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void 申请到核销HTTP主流程与错误语义() throws Exception {
        String applyBody = """
                {"requestId":"REQ-API-1","vehicleCode":"VEH-901","hazmatLevel":1,
                 "routeId":%d,"plannedDeparture":"2026-06-01T10:00:00"}
                """.formatted(routeId("R-LOW"));
        MvcResult applied = mvc.perform(post("/api/waybills/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        long id = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(applied.getResponse().getContentAsString(),
                        "$.id").toString());

        // 同键同参重放 → 200 同结果
        mvc.perform(post("/api/waybills/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        // 同键异参 → 409
        String conflictBody = applyBody.replace("\"hazmatLevel\":1", "\"hazmatLevel\":2");
        mvc.perform(post("/api/waybills/apply")
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        // 参数非法 → 400
        mvc.perform(post("/api/waybills/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-API-2\"}"))
                .andExpect(status().isBadRequest());
        // 路线不存在 → 404
        mvc.perform(post("/api/waybills/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-API-3","vehicleCode":"VEH-902","hazmatLevel":1,
                                 "routeId":99999,"plannedDeparture":"2026-06-01T10:00:00"}
                                """))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/waybills/{id}/approve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/waybills/{id}/depart", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 打卡 → 200；逆序 → 422
        mvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"CK-API-1","waybillId":%d,"waypointSeq":1,
                                 "checkinTime":"2026-06-01T11:00:00"}
                                """.formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(post("/api/checkins").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"CK-API-2","waybillId":%d,"waypointSeq":2,
                                 "checkinTime":"2026-06-01T10:30:00"}
                                """.formatted(id)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));

        // 核销 → 200；重复核销 → 幂等 200
        mvc.perform(post("/api/waybills/{id}/close", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(post("/api/waybills/{id}/close", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        // 运单不存在 → 404
        mvc.perform(get("/api/waybills/{id}", 99999))
                .andExpect(status().isNotFound());
    }
}
