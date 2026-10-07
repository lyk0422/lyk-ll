package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 联调 HTTP 主流程：建组 → 40/60 联调到 50/50 → 查询复算 → 解除；
 * 覆盖 404/409/422 错误语义与幂等重放。
 */
@AutoConfigureMockMvc
class CoupledApiTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void setOpening(long unitId, String opening) {
        jdbcTemplate.update("UPDATE valve SET opening_percent = ? WHERE unit_id = ?",
                new BigDecimal(opening), unitId);
    }

    @Test
    void 建组联调查询解除全流程() throws Exception {
        setOpening(1L, "40.00");
        setOpening(2L, "60.00");

        // 建组：阀 1、2，权重均 1，冻结当前版本 0
        mockMvc.perform(post("/api/stations/1/adjustment-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-g1\",\"members\":["
                                + "{\"valveId\":1,\"weight\":1},{\"valveId\":2,\"weight\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.id").value(1))
                .andExpect(jsonPath("$.group.version").value(1))
                .andExpect(jsonPath("$.group.status").value("ACTIVE"))
                .andExpect(jsonPath("$.members[0].weight").value(1))
                .andExpect(jsonPath("$.members[0].frozenValveVersion").value(0));

        // 同键重放返回首次结果；同键异参 409
        mockMvc.perform(post("/api/stations/1/adjustment-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-g1\",\"members\":["
                                + "{\"valveId\":1,\"weight\":1},{\"valveId\":2,\"weight\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.id").value(1));
        mockMvc.perform(post("/api/stations/1/adjustment-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-g1\",\"members\":["
                                + "{\"valveId\":1,\"weight\":2},{\"valveId\":2,\"weight\":1}]}"))
                .andExpect(status().isConflict());

        // 净额不守恒：422，全组零变化
        mockMvc.perform(post("/api/adjustment-groups/1/coupled-adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-c0\",\"expectedVersion\":1,"
                                + "\"requestedAt\":\"2026-09-26T01:00:00Z\",\"targets\":["
                                + "{\"valveId\":1,\"expectedValveVersion\":0,\"targetOpening\":50},"
                                + "{\"valveId\":2,\"expectedValveVersion\":0,\"targetOpening\":55}]}"))
                .andExpect(status().isUnprocessableEntity());

        // 联调提交：40/60 → 50/50，加权 50+50 = 40+60 守恒
        mockMvc.perform(post("/api/adjustment-groups/1/coupled-adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-c1\",\"expectedVersion\":1,"
                                + "\"requestedAt\":\"2026-09-26T01:00:00Z\",\"targets\":["
                                + "{\"valveId\":1,\"expectedValveVersion\":0,\"targetOpening\":50},"
                                + "{\"valveId\":2,\"expectedValveVersion\":0,\"targetOpening\":50}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupled.id").value(1))
                .andExpect(jsonPath("$.coupled.groupVersion").value(1))
                .andExpect(jsonPath("$.items[0].oldOpening").value(40.00))
                .andExpect(jsonPath("$.items[0].newOpening").value(50.00))
                .andExpect(jsonPath("$.items[1].oldOpening").value(60.00))
                .andExpect(jsonPath("$.items[1].newOpening").value(50.00));

        // 同键重放原响应
        mockMvc.perform(post("/api/adjustment-groups/1/coupled-adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-c1\",\"expectedVersion\":1,"
                                + "\"requestedAt\":\"2026-09-26T01:00:00Z\",\"targets\":["
                                + "{\"valveId\":1,\"expectedValveVersion\":0,\"targetOpening\":50},"
                                + "{\"valveId\":2,\"expectedValveVersion\":0,\"targetOpening\":50}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coupled.id").value(1));

        // 查询组：当前开度 50/50，净加权开度可复算为 100；联调单版本已推进为 2
        mockMvc.perform(get("/api/adjustment-groups/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.version").value(2))
                .andExpect(jsonPath("$.members[0].currentOpening").value(50.00))
                .andExpect(jsonPath("$.members[0].currentValveVersion").value(1))
                .andExpect(jsonPath("$.members[1].currentOpening").value(50.00));

        // 查询联调记录明细
        mockMvc.perform(get("/api/coupled-adjustments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].weight").value(1));
        mockMvc.perform(get("/api/adjustment-groups/1/coupled-adjustments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 旧版联调提交：409
        mockMvc.perform(post("/api/adjustment-groups/1/coupled-adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-c2\",\"expectedVersion\":1,"
                                + "\"requestedAt\":\"2026-09-26T02:00:00Z\",\"targets\":["
                                + "{\"valveId\":1,\"expectedValveVersion\":1,\"targetOpening\":40},"
                                + "{\"valveId\":2,\"expectedValveVersion\":1,\"targetOpening\":60}]}"))
                .andExpect(status().isConflict());

        // 解除：仅释放归属；同键重放；解除后联调 422
        mockMvc.perform(post("/api/adjustment-groups/1/dissolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-d1\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISSOLVED"))
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(post("/api/adjustment-groups/1/dissolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-d1\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISSOLVED"));
        mockMvc.perform(post("/api/adjustment-groups/1/coupled-adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-api-c3\",\"expectedVersion\":3,"
                                + "\"requestedAt\":\"2026-09-26T03:00:00Z\",\"targets\":["
                                + "{\"valveId\":1,\"expectedValveVersion\":1,\"targetOpening\":40},"
                                + "{\"valveId\":2,\"expectedValveVersion\":1,\"targetOpening\":60}]}"))
                .andExpect(status().isUnprocessableEntity());

        // 不存在的组：404
        mockMvc.perform(get("/api/adjustment-groups/999"))
                .andExpect(status().isNotFound());
    }
}
