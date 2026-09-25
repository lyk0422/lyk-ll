package com.example.starter;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层：申请→审批→备案→回传→评估主流程及幂等冲突错误语义。
 */
@AutoConfigureMockMvc
class ApiWebTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        String applyBody = """
                {"requestId":"web-1","zoneId":1,"applicant":"张三",
                 "plannedBlastTime":"2026-09-26T08:00:00Z","chargePerDelay":10}
                """;
        MvcResult applied = mvc.perform(post("/api/permits")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        long permitId = ((Number) JsonPath.read(applied.getResponse().getContentAsString(),
                "$.id")).longValue();

        // 同键同参重放返回首次结果
        mvc.perform(post("/api/permits").contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(permitId));

        // 同键异参返回 409 与稳定错误码
        String conflictBody = applyBody.replace("2026-09-26T08:00:00Z", "2026-09-26T09:00:00Z");
        mvc.perform(post("/api/permits").contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mvc.perform(post("/api/permits/{id}/approve", permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"web-1-F\",\"permitId\":" + permitId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permitId").value(permitId));

        mvc.perform(post("/api/vibration-readings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permitId\":" + permitId + ",\"pointCode\":\"P1\","
                                + "\"sampledAt\":\"2026-09-26T08:00:01Z\",\"velocity\":0.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
        mvc.perform(post("/api/vibration-readings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permitId\":" + permitId + ",\"pointCode\":\"P2\","
                                + "\"sampledAt\":\"2026-09-26T08:00:02Z\",\"velocity\":0.6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        mvc.perform(post("/api/permits/{id}/evaluations", permitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"web-1-E\",\"conclusion\":\"合格\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value(true));

        mvc.perform(get("/api/permits/{id}", permitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));
    }

    @Test
    void freezeAndUnfreezeOverHttp() throws Exception {
        MvcResult applied = mvc.perform(post("/api/permits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"web-2\",\"zoneId\":2,\"applicant\":\"李四\","
                                + "\"plannedBlastTime\":\"2026-09-26T08:00:00Z\",\"chargePerDelay\":10}"))
                .andExpect(status().isOk())
                .andReturn();
        long permitId = ((Number) JsonPath.read(applied.getResponse().getContentAsString(),
                "$.id")).longValue();

        mvc.perform(post("/api/permits/{id}/approve", permitId)).andExpect(status().isOk());
        mvc.perform(post("/api/filings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"web-2-F\",\"permitId\":" + permitId + "}"))
                .andExpect(status().isOk());

        // 超限回传触发冻结
        mvc.perform(post("/api/vibration-readings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permitId\":" + permitId + ",\"pointCode\":\"P1\","
                                + "\"sampledAt\":\"2026-09-26T08:00:01Z\",\"velocity\":2.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freezeTriggered").value(true));

        // 复核解冻，重放幂等
        String review = "{\"requestId\":\"web-2-R\",\"mineId\":1,\"reviewer\":\"监管员甲\"}";
        mvc.perform(post("/api/freeze-reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(review))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unfrozen").value(true));
        mvc.perform(post("/api/freeze-reviews").contentType(MediaType.APPLICATION_JSON)
                        .content(review))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unfrozen").value(false));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        mvc.perform(post("/api/permits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"\",\"zoneId\":1,\"applicant\":\"张三\","
                                + "\"plannedBlastTime\":\"2026-09-26T08:00:00Z\",\"chargePerDelay\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
