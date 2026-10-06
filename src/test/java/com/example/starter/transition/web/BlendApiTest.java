package com.example.starter.transition.web;

import com.example.starter.transition.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 掺配比例 REST 契约测试：恰达边界比例申请 201、越界 400、调整/读数/失控/恢复/复算链路。
 */
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class BlendApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("HTTP掺配链路：恰30.000边界比例申请201→判定→执行→调整→连续超差失控→复核恢复→复算")
    void blendHttpFlowWithBoundaryRatio() throws Exception {
        // 申请：掺配比例恰达合成允许区间上边界 30.000%，合法受理。
        String applyBody = """
                {"requestId":"api-blend-1","paperMachine":"PM-WB1","fromGrade":"G-A4-70","toGrade":"G-A4-77","targetBlendRatioPct":30.000}
                """;
        String response = mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andReturn().getResponse().getContentAsString();
        String orderNo = com.jayway.jsonpath.JsonPath.read(response, "$.data.orderNo");

        mockMvc.perform(post("/api/transitions/orders/{no}/decide", orderNo))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/transitions/orders/{no}/execute", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machineSpeedMMin":820.000,"stockConsistencyPct":0.850,"switchTimeSec":120}
                                """))
                .andExpect(status().isOk());

        // 执行中调整：30.000 → 25.000，差值恰 5% 在线直调，判定结论与版本同事务落库。
        mockMvc.perform(post("/api/transitions/orders/{no}/blend-ratios", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-blend-adj-1","targetBlendRatioPct":25.000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.adjustMode").value("DIRECT_ADJUST"))
                .andExpect(jsonPath("$.data.ratioDeltaPct").value(5.0000));

        // 读数：恰 2% 偏差合格。
        mockMvc.perform(post("/api/transitions/orders/{no}/blend-readings", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-blend-rd-1","sampledAt":"2026-09-25T10:00:00","actualRatioPct":27.000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.withinTolerance").value(true))
                .andExpect(jsonPath("$.data.outOfControl").value(false));

        // 连续 3 点超差（偏差 3%）判失控，暂停转人工。
        for (int i = 2; i <= 4; i++) {
            String body = """
                    {"requestId":"api-blend-rd-%d","sampledAt":"2026-09-25T10:0%d:00","actualRatioPct":28.000}
                    """.formatted(i, i);
            var result = mockMvc.perform(post("/api/transitions/orders/{no}/blend-readings", orderNo)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
            if (i == 4) {
                result.andExpect(jsonPath("$.data.outOfControl").value(true))
                        .andExpect(jsonPath("$.data.controlStatus").value("PAUSED_MANUAL"));
            }
        }

        // 暂停期间处置被阻断：409。
        mockMvc.perform(post("/api/transitions/orders/{no}/dispose", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rolls":[{"rollNo":"RW-BLEND-1","lengthM":100.000}]}
                                """))
                .andExpect(status().isConflict());

        // 复核确认恢复。
        mockMvc.perform(post("/api/transitions/orders/{no}/blend-control/resume", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-blend-resume-1","reviewer":"checker-api"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.controlStatus").value("RUNNING"));

        // 复算：版本链两条，版本 2 判失控，事件两条。
        mockMvc.perform(get("/api/transitions/orders/{no}/blend-recompute", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(2)))
                .andExpect(jsonPath("$.versions[1].outOfControl").value(true))
                .andExpect(jsonPath("$.versions[1].readings", hasSize(4)))
                .andExpect(jsonPath("$.events", hasSize(2)));
    }

    @Test
    @DisplayName("掺配比例越界申请返回400；未设比例订单调整返回409")
    void blendValidationAndConflict() throws Exception {
        mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-blend-oor","paperMachine":"PM-WB2","fromGrade":"G-A4-70","toGrade":"G-A4-77","targetBlendRatioPct":30.001}
                                """))
                .andExpect(status().isBadRequest());

        String response = mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-blend-noratio","paperMachine":"PM-WB3","fromGrade":"G-A4-70","toGrade":"G-A4-77"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderNo = com.jayway.jsonpath.JsonPath.read(response, "$.data.orderNo");
        mockMvc.perform(post("/api/transitions/orders/{no}/decide", orderNo)).andExpect(status().isOk());
        mockMvc.perform(post("/api/transitions/orders/{no}/execute", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machineSpeedMMin":820.000,"stockConsistencyPct":0.850,"switchTimeSec":120}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/transitions/orders/{no}/blend-ratios", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-blend-noratio-adj","targetBlendRatioPct":20.000}
                                """))
                .andExpect(status().isConflict());
    }
}
