package com.example.starter;

import java.math.BigDecimal;

import com.example.starter.dto.GrainDtos.CreateFanRequest;
import com.example.starter.dto.GrainDtos.CreateSiloRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.SiloView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 熏蒸 HTTP 接口测试：覆盖真实入口的申请、双审批、开始、转散气、采样、解除主流程与
 * 400 参数非法、404 不存在、409 幂等/版本冲突、422 业务拒绝状态码语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class FumigationControllerTest {

    private static final String U0 = "2026-09-26T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private com.example.starter.service.SiloService siloService;

    private long newSilo() {
        SiloView silo = siloService.createSilo(new CreateSiloRequest(
                "S-WEB-" + System.nanoTime(), "web粮仓", new BigDecimal("30.00"), new BigDecimal("70.00")));
        return silo.id();
    }

    private long newFan() {
        FanView fan = siloService.createFan(new CreateFanRequest(
                "F-WEB-" + System.nanoTime(), "web风机"));
        return fan.id();
    }

    @Test
    void fullFlowThroughHttpEndpoints() throws Exception {
        long siloId = newSilo();
        long fanId = newFan();

        // 申请 201
        String applyBody = "{\"requestId\":\"w-apply\",\"windowStart\":\"" + U0 + "\","
                + "\"windowEnd\":\"2026-09-26T01:00:00Z\",\"syntheticAgent\":\"SYN-A\","
                + "\"safetyThreshold\":5.000}";
        String applyResponse = mockMvc.perform(post("/api/silos/{id}/fumigation-orders", siloId)
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(applyResponse, "$.id")).longValue();

        // 同键重放 200
        mockMvc.perform(post("/api/silos/{id}/fumigation-orders", siloId)
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 双审批
        mockMvc.perform(post("/api/fumigation-orders/{id}/approvals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-a1\",\"expectedVersion\":1,\"approver\":\"alice\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/fumigation-orders/{id}/approvals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-a2\",\"expectedVersion\":2,\"approver\":\"bob\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 开始
        mockMvc.perform(post("/api/fumigation-orders/{id}/start", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-start\",\"expectedVersion\":3,\"startedAt\":\"" + U0 + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FUMIGATING"))
                .andExpect(jsonPath("$.frozenPlanVersion").value(1));

        // 转散气
        mockMvc.perform(post("/api/fumigation-orders/{id}/aeration", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-aer\",\"expectedVersion\":4,\"fanId\":" + fanId
                                + ",\"aerationStartedAt\":\"2026-09-26T00:10:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AERATING"));

        // 三点安全采样（恰等阈值、首末恰 30 分钟）
        reading(orderId, "w-g1", 5, 1, "2026-09-26T01:00:00Z");
        reading(orderId, "w-g2", 6, 2, "2026-09-26T01:15:00Z");
        reading(orderId, "w-g3", 7, 3, "2026-09-26T01:30:00Z");

        // 查询读数
        mockMvc.perform(get("/api/fumigation-orders/{id}/gas-readings", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // 解除
        mockMvc.perform(post("/api/fumigation-orders/{id}/release", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-rel\",\"expectedVersion\":8,"
                                + "\"reviewers\":[\"carol\",\"dave\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstSeqNo").value(1))
                .andExpect(jsonPath("$.lastSeqNo").value(3));

        mockMvc.perform(get("/api/fumigation-orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mockMvc.perform(get("/api/fumigation-orders/{id}/release", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewers.length()").value(2));
    }

    @Test
    void badRequestForIllegalWindow() throws Exception {
        long siloId = newSilo();
        mockMvc.perform(post("/api/silos/{id}/fumigation-orders", siloId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-bad\",\"windowStart\":\"2026-09-26T01:00:00Z\","
                                + "\"windowEnd\":\"2026-09-26T00:00:00Z\",\"syntheticAgent\":\"A\","
                                + "\"safetyThreshold\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
    }

    @Test
    void validationErrorForMissingField() throws Exception {
        long siloId = newSilo();
        mockMvc.perform(post("/api/silos/{id}/fumigation-orders", siloId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowStart\":\"" + U0 + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void notFoundForMissingOrder() throws Exception {
        mockMvc.perform(get("/api/fumigation-orders/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FUMIGATION_NOT_FOUND"));
    }

    @Test
    void conflictForIdempotencyReplayWithDifferentParams() throws Exception {
        long siloId = newSilo();
        String body = "{\"requestId\":\"w-cf\",\"windowStart\":\"" + U0 + "\","
                + "\"windowEnd\":\"2026-09-26T01:00:00Z\",\"syntheticAgent\":\"A\","
                + "\"safetyThreshold\":5}";
        mockMvc.perform(post("/api/silos/{id}/fumigation-orders", siloId)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/silos/{id}/fumigation-orders", siloId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-cf\",\"windowStart\":\"" + U0 + "\","
                                + "\"windowEnd\":\"2026-09-26T02:00:00Z\",\"syntheticAgent\":\"A\","
                                + "\"safetyThreshold\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void unprocessableForBusinessRejection() throws Exception {
        long siloId = newSilo();
        String body = "{\"requestId\":\"w-422\",\"windowStart\":\"" + U0 + "\","
                + "\"windowEnd\":\"2026-09-26T01:00:00Z\",\"syntheticAgent\":\"A\","
                + "\"safetyThreshold\":5}";
        String response = mockMvc.perform(post("/api/silos/{id}/fumigation-orders", siloId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.id")).longValue();

        // 未双审批即开始：422
        mockMvc.perform(post("/api/fumigation-orders/{id}/start", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-early-start\",\"expectedVersion\":1,"
                                + "\"startedAt\":\"" + U0 + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
    }

    private void reading(long orderId, String requestId, int version, int seq, String measuredAt)
            throws Exception {
        mockMvc.perform(post("/api/fumigation-orders/{id}/gas-readings", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version
                                + ",\"seqNo\":" + seq + ",\"concentration\":5.000,\"measuredAt\":\""
                                + measuredAt + "\"}"))
                .andExpect(status().isCreated());
    }
}
