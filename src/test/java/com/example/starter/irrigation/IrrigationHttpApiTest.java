package com.example.starter.irrigation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.BeforeEach;

/**
 * HTTP 端到端测试：编组、发布、幂等重放、异参 409、计量去重、加购、封存终态
 * 与迟到计量的完整链路及状态码语义。
 */
class IrrigationHttpApiTest extends AbstractIntegrationTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @BeforeEach
    void setupMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void fullChainGroupPublishMeterPurchaseSealLateReport() throws Exception {
        // 1. 编组：三斗口等面积
        mockMvc.perform(post("/api/irrigation/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupCode":"G-HTTP","groupName":"HTTP全链路组","outlets":[
                                  {"outletCode":"D001","outletName":"东斗口","areaSquareMeter":1},
                                  {"outletCode":"D002","outletName":"西斗口","areaSquareMeter":1},
                                  {"outletCode":"D003","outletName":"北斗口","areaSquareMeter":1}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outlets.length()").value(3));

        mockMvc.perform(get("/api/irrigation/groups/G-HTTP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupCode").value("G-HTTP"));

        // 2. 发布 T=3000 轮计划，总量 10：等面积权重 0.3333，差额归 D001 => 4/3/3
        mockMvc.perform(post("/api/irrigation/plans/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-req-1","groupCode":"G-HTTP","turnNo":3000,
                                 "availableCubicMeter":10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.lines[0].outletCode").value("D001"))
                .andExpect(jsonPath("$.lines[0].planCubicMeter").value(4))
                .andExpect(jsonPath("$.lines[1].planCubicMeter").value(3))
                .andExpect(jsonPath("$.lines[2].planCubicMeter").value(3))
                .andExpect(jsonPath("$.lines[0].carryoverCubicMeter").value(0));

        // 3. 同键同参重放：200 语义下仍返回首次结果（POST 幂等），版本号不变
        mockMvc.perform(post("/api/irrigation/plans/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-req-1","groupCode":"G-HTTP","turnNo":3000,
                                 "availableCubicMeter":10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1));

        // 4. 同键异参：409，无新版本
        mockMvc.perform(post("/api/irrigation/plans/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-req-1","groupCode":"G-HTTP","turnNo":3000,
                                 "availableCubicMeter":11}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 5. 无基线二次发布：409
        mockMvc.perform(post("/api/irrigation/plans/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-req-2","groupCode":"G-HTTP","turnNo":3000,
                                 "availableCubicMeter":20}
                                """))
                .andExpect(status().isConflict());

        // 6. 计量上报：D001 实供 2
        mockMvc.perform(post("/api/irrigation/meterings/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"http-m1","groupCode":"G-HTTP","outletCode":"D001",
                                 "turnNo":3000,"deliveredCubicMeter":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.late").value(false))
                .andExpect(jsonPath("$.deliveredCubicMeter").value(2));

        // 同 eventId 重放：duplicate=true，读数不变
        mockMvc.perform(post("/api/irrigation/meterings/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"http-m1","groupCode":"G-HTTP","outletCode":"D001",
                                 "turnNo":3000,"deliveredCubicMeter":999}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.deliveredCubicMeter").value(2));

        // 7. 加购 3 m3：欠供 D001=2、D002=3、D003=3，按欠供比例分配
        mockMvc.perform(post("/api/irrigation/purchases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-pur-1","groupCode":"G-HTTP","turnNo":3000,
                                 "amountCubicMeter":3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocatedCubicMeter").value(3));

        // 8. 封存：幂等首次
        mockMvc.perform(post("/api/irrigation/settlements/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupCode":"G-HTTP","turnNo":3000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.lines.length()").value(3));

        // 重复封存：幂等成功
        mockMvc.perform(post("/api/irrigation/settlements/seal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupCode":"G-HTTP","turnNo":3000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 9. 封存后迟到计量：late=true，不改结转
        mockMvc.perform(post("/api/irrigation/meterings/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"http-late-1","groupCode":"G-HTTP","outletCode":"D002",
                                 "turnNo":3000,"deliveredCubicMeter":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(true));

        // 10. 封存后加购：422
        mockMvc.perform(post("/api/irrigation/purchases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-pur-late","groupCode":"G-HTTP","turnNo":3000,
                                 "amountCubicMeter":3}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 11. 旧版本可查
        mockMvc.perform(get("/api/irrigation/plans/groups/G-HTTP/turns/3000/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1));

        // 12. 参数校验失败：400
        mockMvc.perform(post("/api/irrigation/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"groupCode":"","groupName":"","outlets":[]}
                                """))
                .andExpect(status().isBadRequest());
    }
}
