package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 联调 HTTP 主流程与错误语义：建组 → 40/60 联调到 50/50 → 查询复算；
 * 净额不守恒 422、旧版 409、缺组 404。
 */
@AutoConfigureMockMvc
class CoupledApiTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 建组联调查询全流程() throws Exception {
        jdbc.update("UPDATE valve SET opening_percent = 40.00 WHERE id = 1");
        jdbc.update("UPDATE valve SET opening_percent = 60.00 WHERE id = 2");

        // 建组：同权阀 1、2，版本 1 起
        mockMvc.perform(post("/api/stations/1/coupled-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-cg-1\",\"members\":["
                                + "{\"valveId\":1,\"weight\":1},{\"valveId\":2,\"weight\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.status").value("ACTIVE"))
                .andExpect(jsonPath("$.group.version").value(1))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].weight").value(1));

        // 联调：40/60 → 50/50，加权总和 100 = 100
        mockMvc.perform(post("/api/coupled-groups/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-ca-1\",\"adjustAt\":\"2026-09-26T00:00:00Z\","
                                + "\"targets\":["
                                + "{\"valveId\":1,\"targetOpening\":50,\"currentVersion\":0},"
                                + "{\"valveId\":2,\"targetOpening\":50,\"currentVersion\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustment.version").value(1))
                .andExpect(jsonPath("$.weightedBefore").value(100))
                .andExpect(jsonPath("$.weightedAfter").value(100))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].oldOpening").value(40))
                .andExpect(jsonPath("$.items[0].newOpening").value(50))
                .andExpect(jsonPath("$.items[1].oldOpening").value(60))
                .andExpect(jsonPath("$.items[1].newOpening").value(50));

        // 同参重放：同键同参返回首次结果
        mockMvc.perform(post("/api/coupled-groups/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-ca-1\",\"adjustAt\":\"2026-09-26T00:00:00Z\","
                                + "\"targets\":["
                                + "{\"valveId\":1,\"targetOpening\":50,\"currentVersion\":0},"
                                + "{\"valveId\":2,\"targetOpening\":50,\"currentVersion\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustment.id").value(1));

        // 查询：联调前后开度留痕，可复算净加权开度
        mockMvc.perform(get("/api/coupled-groups/1/adjustments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].weightedBefore").value(100))
                .andExpect(jsonPath("$[0].weightedAfter").value(100));
    }

    @Test
    void 错误语义映射() throws Exception {
        jdbc.update("UPDATE valve SET opening_percent = 40.00 WHERE id = 1");
        jdbc.update("UPDATE valve SET opening_percent = 60.00 WHERE id = 2");
        mockMvc.perform(post("/api/stations/1/coupled-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-cg-2\",\"members\":["
                                + "{\"valveId\":1,\"weight\":1},{\"valveId\":2,\"weight\":1}]}"))
                .andExpect(status().isOk());

        // 净额不守恒：40+60=100 ≠ 51+50=101 → 422，全组零变化
        mockMvc.perform(post("/api/coupled-groups/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-ca-nc\",\"adjustAt\":\"2026-09-26T00:00:00Z\","
                                + "\"targets\":["
                                + "{\"valveId\":1,\"targetOpening\":51,\"currentVersion\":0},"
                                + "{\"valveId\":2,\"targetOpening\":50,\"currentVersion\":0}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("UnprocessableException"));

        // 旧版本：阀 2 提交版本 1（实际 0）→ 409
        mockMvc.perform(post("/api/coupled-groups/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-ca-ov\",\"adjustAt\":\"2026-09-26T00:00:00Z\","
                                + "\"targets\":["
                                + "{\"valveId\":1,\"targetOpening\":50,\"currentVersion\":0},"
                                + "{\"valveId\":2,\"targetOpening\":50,\"currentVersion\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ConflictException"));

        // 改组旧版 → 409
        mockMvc.perform(post("/api/coupled-groups/1/modify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":9,\"members\":["
                                + "{\"valveId\":1,\"weight\":1},{\"valveId\":3,\"weight\":1}]}"))
                .andExpect(status().isConflict());

        // 缺组 → 404
        mockMvc.perform(get("/api/coupled-groups/99"))
                .andExpect(status().isNotFound());

        // 失败不占键：修正净额后同键受理成功
        mockMvc.perform(post("/api/coupled-groups/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-ca-nc\",\"adjustAt\":\"2026-09-26T00:00:00Z\","
                                + "\"targets\":["
                                + "{\"valveId\":1,\"targetOpening\":50,\"currentVersion\":0},"
                                + "{\"valveId\":2,\"targetOpening\":50,\"currentVersion\":0}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightedAfter").value(100));
    }
}
