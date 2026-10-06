package com.example.starter.transition.web;

import com.example.starter.transition.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API 契约测试：申请 201、同键重放 200、异参 409、判定/执行/处置链路与复算接口。
 */
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(AbstractIntegrationTest.FixedClockConfiguration.class)
class TransitionApiTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanBusinessTables(jdbc);
    }

    @Test
    @DisplayName("HTTP全链路：申请201→重放200→异参409→判定→执行→处置→复算")
    void fullHttpFlow() throws Exception {
        // 申请：201
        String applyBody = """
                {"requestId":"api-1","paperMachine":"PM-W1","fromGrade":"G-A4-70","toGrade":"G-A4-77"}
                """;
        String response = mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andReturn().getResponse().getContentAsString();

        String orderNo = com.jayway.jsonpath.JsonPath.read(response, "$.data.orderNo");

        // 同键同参重放：200 + replayed=true
        mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));

        // 同键异参：409
        mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-1","paperMachine":"PM-W1","fromGrade":"G-A4-70","toGrade":"G-A4-80"}
                                """))
                .andExpect(status().isConflict());

        // 判定：恰 10% 直切、灰分恰 2 级不冲洗
        mockMvc.perform(post("/api/transitions/orders/{no}/decide", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directSwitch").value(true))
                .andExpect(jsonPath("$.wireFlush").value(false))
                .andExpect(jsonPath("$.basisWeightDeltaPct").value(10.0000));

        // 越界执行：400（单据仍为 DECIDED，先校验工艺窗口）
        mockMvc.perform(post("/api/transitions/orders/{no}/execute", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machineSpeedMMin":819.999,"stockConsistencyPct":0.850,"switchTimeSec":120}
                                """))
                .andExpect(status().isBadRequest());

        // 执行：窗口下边界参数合格；过渡料 820×120/60=1640
        mockMvc.perform(post("/api/transitions/orders/{no}/execute", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machineSpeedMMin":820.000,"stockConsistencyPct":0.850,"switchTimeSec":120}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.transitionLengthM").value(1640.000));

        // 过渡料处置：两卷强制降级
        mockMvc.perform(post("/api/transitions/orders/{no}/dispose", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rolls":[{"rollNo":"RW-1","lengthM":800.000},{"rollNo":"RW-2","lengthM":840.000}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPOSED"));

        mockMvc.perform(get("/api/transitions/rolls/RW-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("DOWNGRADED"))
                .andExpect(jsonPath("$.warehouseStatus").value("NON_GENUINE"))
                .andExpect(jsonPath("$.chain", hasSize(1)));

        // 复算：记录长度=重算长度，卷明细两卷
        mockMvc.perform(get("/api/transitions/orders/{no}/recompute", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordedTransitionLengthM").value(1640.000))
                .andExpect(jsonPath("$.recomputedTransitionLengthM").value(1640.000))
                .andExpect(jsonPath("$.sumRollLengthM").value(1640.000))
                .andExpect(jsonPath("$.rolls", hasSize(2)));

        // 完成 + 归档 + 重复归档
        mockMvc.perform(post("/api/transitions/orders/{no}/complete", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mockMvc.perform(post("/api/transitions/orders/{no}/archive", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(post("/api/transitions/orders/{no}/archive", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    @DisplayName("参数校验失败返回400；未知单号返回404")
    void validationAndNotFound() throws Exception {
        mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","paperMachine":"PM-X","fromGrade":"G-A4-70","toGrade":"G-A4-77"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/transitions/orders/TO-NOT-EXIST"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("HTTP掺配链路：恰达边界比例申请201→读数恰2%合格→连续3超差暂停→复核恢复→复算")
    void blendRatioHttpFlow() throws Exception {
        // 申请携带恰达上边界 25.000% 的掺配比例：201，同事务完成方式判定
        String applyBody = """
                {"requestId":"api-blend-1","paperMachine":"PM-WB1","fromGrade":"G-A4-70","toGrade":"G-A4-77","targetBlendRatioPct":25.000}
                """;
        String response = mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DECIDED"))
                .andExpect(jsonPath("$.data.currentBlendVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String orderNo = com.jayway.jsonpath.JsonPath.read(response, "$.data.orderNo");

        // 比例越界申请：400
        mockMvc.perform(post("/api/transitions/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-blend-2","paperMachine":"PM-WB2","fromGrade":"G-A4-70","toGrade":"G-A4-77","targetBlendRatioPct":25.001}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/transitions/orders/{no}/execute", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"machineSpeedMMin":900.000,"stockConsistencyPct":1.000,"switchTimeSec":60}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        // 恰 2% 偏离合格
        mockMvc.perform(post("/api/transitions/blend-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-rd-1","orderNo":"%s","sampledAt":"2026-09-25T12:00:00","actualRatioPct":27.000}
                                """.formatted(orderNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.outOfTolerance").value(false))
                .andExpect(jsonPath("$.data.deviationPct").value(2.000));

        // 同一订单同一采样时刻去重：409
        mockMvc.perform(post("/api/transitions/blend-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-rd-2","orderNo":"%s","sampledAt":"2026-09-25T12:00:00","actualRatioPct":26.000}
                                """.formatted(orderNo)))
                .andExpect(status().isConflict());

        // 连续 3 个超差点 → 失控暂停转人工
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/transitions/blend-readings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"api-rd-x%d","orderNo":"%s","sampledAt":"2026-09-25T12:0%d:00","actualRatioPct":28.000}
                                    """.formatted(i, orderNo, i)))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(get("/api/transitions/orders/{no}", orderNo))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.consecutiveOutOfTolerance").value(3));

        // 未经复核确认恢复：400；复核确认恢复：200 回到 EXECUTED
        mockMvc.perform(post("/api/transitions/orders/{no}/resume", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-rs-1","reviewedBy":"checker-1","reviewConfirmed":false}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/transitions/orders/{no}/resume", orderNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-rs-2","reviewedBy":"checker-1","reviewConfirmed":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.consecutiveOutOfTolerance").value(0));

        // 版本链与掺配轨迹复算
        mockMvc.perform(get("/api/transitions/orders/{no}/blend-versions", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].targetRatioPct").value(25.000));
        mockMvc.perform(get("/api/transitions/orders/{no}/blend-recompute", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(1)))
                .andExpect(jsonPath("$.readings", hasSize(4)))
                .andExpect(jsonPath("$.readings[0].outOfTolerance").value(false))
                .andExpect(jsonPath("$.readings[1].outOfTolerance").value(true));
    }
}
