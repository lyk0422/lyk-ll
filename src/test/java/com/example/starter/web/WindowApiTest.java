package com.example.starter.web;

import com.example.starter.support.AbstractWindowTest;
import com.example.starter.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 层测试：建窗、幂等重放、冲突拒绝、审批生效、完成离泊的 HTTP 主流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class WindowApiTest extends AbstractWindowTest {

    @Autowired
    private MockMvc mvc;

    private String createBody(String requestId, long berthId, String start, String end,
            String draft) {
        return """
                {"requestId":"%s","vesselName":"VESSEL-%s","berthId":%d,
                 "startAt":"%s","endAt":"%s","draftM":%s,"pilotsRequired":1}
                """.formatted(requestId, requestId, berthId, start, end, draft);
    }

    @Test
    void 建窗审批生效完成离泊主流程() throws Exception {
        String body = createBody("REQ-API-1", berthA,
                "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z", "6.00");

        String created = mvc.perform(post("/api/windows")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestId").value("REQ-API-1"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

        // 同键同参重放：200 且同一窗口
        mvc.perform(post("/api/windows").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        // 同键异参：409
        mvc.perform(post("/api/windows").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("REQ-API-1", berthA,
                                "2026-10-01T10:00:00Z", "2026-10-01T13:00:00Z", "6.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_KEY_CONFLICT"));

        // 审批
        mvc.perform(post("/api/windows/{id}/approve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.pilotIds.length()").value(1));

        // 到达起始时刻自动生效
        clock.set(at("2026-10-01T10:30:00Z"));
        mvc.perform(get("/api/windows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 登记离泊完成，重复登记幂等
        mvc.perform(post("/api/windows/{id}/complete", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/windows/{id}/complete", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 冲突拒绝与限深拒绝() throws Exception {
        String first = createBody("REQ-API-2", berthA,
                "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z", "6.00");
        String created = mvc.perform(post("/api/windows")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();
        mvc.perform(post("/api/windows/{id}/approve", id)).andExpect(status().isOk());

        // 内部重叠：409
        mvc.perform(post("/api/windows").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("REQ-API-3", berthA,
                                "2026-10-01T11:00:00Z", "2026-10-01T13:00:00Z", "6.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BERTH_WINDOW_CONFLICT"));

        // 吃水超 BERTH-B 限深 8.5：422 且不产生记录
        mvc.perform(post("/api/windows").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("REQ-API-4", berthB,
                                "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z", "9.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRAFT_EXCEEDS_LIMIT"));
        assertThat(countWindows()).isEqualTo(1);
    }
}
