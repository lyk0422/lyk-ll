package com.example.starter;

import java.math.BigDecimal;

import com.example.starter.dto.GrainDtos.CreateFanRequest;
import com.example.starter.dto.GrainDtos.CreateSiloRequest;
import com.example.starter.dto.GrainDtos.FanView;
import com.example.starter.dto.GrainDtos.SiloView;

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
 * 多测点 HTTP 真实入口测试：两测点登记、漏点整帧拒绝、旧单点入口拒绝、
 * 两点三完整帧（首末恰 30 分钟）后双复核解除、散气中增点使待解除确认失效。
 */
@AutoConfigureMockMvc
class MultiPointControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private long newSilo() {
        SiloView silo = siloService.createSilo(new CreateSiloRequest(
                "S-WEB-MP-" + System.nanoTime(), "多测点web粮仓",
                new BigDecimal("30.00"), new BigDecimal("70.00")));
        return silo.id();
    }

    private long newFan() {
        FanView fan = siloService.createFan(new CreateFanRequest(
                "F-WEB-MP-" + System.nanoTime(), "多测点web风机"));
        return fan.id();
    }

    @Test
    void twoPointsThreeFramesThenReleaseThroughHttp() throws Exception {
        long siloId = newSilo();
        long fanId = newFan();

        long orderId = applyAndApproveStart(siloId, "wm");

        // 熏蒸中（版本4）登记 P1/P2 测点名单，冻结名单版本 1
        mockMvc.perform(post("/api/fumigation-orders/{id}/gas-points", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wm-points\",\"expectedVersion\":4,\"points\":[\"P1\",\"P2\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.listVersion").value(1))
                .andExpect(jsonPath("$.points.length()").value(2));

        // 转散气（版本5）
        mockMvc.perform(post("/api/fumigation-orders/{id}/aeration", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wm-aer\",\"expectedVersion\":5,\"fanId\":" + fanId
                                + ",\"aerationStartedAt\":\"2026-09-26T00:10:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AERATING"));

        // 帧1 缺点（只报 P1，漏 P2）→ 422，整帧拒绝
        mockMvc.perform(post("/api/fumigation-orders/{id}/gas-frames", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(frameBody("wm-f1-missing", 6, 1, "2026-09-26T01:00:00Z",
                                "[[\"P1\",5.0]]")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FRAME_POINT_MISSING"));

        // 受控单走旧单点入口 → 422
        mockMvc.perform(post("/api/fumigation-orders/{id}/gas-readings", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wm-old\",\"expectedVersion\":6,\"seqNo\":1,"
                                + "\"concentration\":4.000,\"measuredAt\":\"2026-09-26T01:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MULTI_POINT_FRAME_REQUIRED"));

        // 三完整帧，P1/P2 全点恰等阈值 5.000，首末恰 30 分钟，版本 6/7/8
        frame(orderId, "wm-f1", 6, 1, "2026-09-26T01:00:00Z", 5.000, 5.000);
        frame(orderId, "wm-f2", 7, 2, "2026-09-26T01:15:00Z", 5.000, 4.000);
        frame(orderId, "wm-f3", 8, 3, "2026-09-26T01:30:00Z", 4.000, 5.000);

        // 帧查询可复算每帧最差值
        mockMvc.perform(get("/api/fumigation-orders/{id}/gas-frames", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].worstConcentration").value(5.000))
                .andExpect(jsonPath("$[0].safe").value(true))
                .andExpect(jsonPath("$[2].readings.length()").value(2));
        // 连续段复算：长度3、可解除、首帧1末帧3
        mockMvc.perform(get("/api/fumigation-orders/{id}/gas-frames/chain", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runLength").value(3))
                .andExpect(jsonPath("$.releasable").value(true))
                .andExpect(jsonPath("$.firstSeqNo").value(1))
                .andExpect(jsonPath("$.lastSeqNo").value(3));

        // 双复核按最新帧与名单版本解除（版本9）
        mockMvc.perform(post("/api/fumigation-orders/{id}/release", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wm-rel\",\"expectedVersion\":9,"
                                + "\"reviewers\":[\"carol\",\"dave\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstSeqNo").value(1))
                .andExpect(jsonPath("$.lastSeqNo").value(3))
                .andExpect(jsonPath("$.listVersion").value(1));

        mockMvc.perform(get("/api/fumigation-orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.multiPoint").value(true));
    }

    @Test
    void addingPointDuringAerationResetsFrameChainThroughHttp() throws Exception {
        long siloId = newSilo();
        long fanId = newFan();
        long orderId = applyAndApproveStart(siloId, "wa");

        mockMvc.perform(post("/api/fumigation-orders/{id}/gas-points", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wa-points\",\"expectedVersion\":4,\"points\":[\"P1\",\"P2\"]}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/fumigation-orders/{id}/aeration", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wa-aer\",\"expectedVersion\":5,\"fanId\":" + fanId
                                + ",\"aerationStartedAt\":\"2026-09-26T00:10:00Z\"}"))
                .andExpect(status().isCreated());

        frame(orderId, "wa-f1", 6, 1, "2026-09-26T01:00:00Z", 4, 4);
        frame(orderId, "wa-f2", 7, 2, "2026-09-26T01:15:00Z", 4, 4);
        frame(orderId, "wa-f3", 8, 3, "2026-09-26T01:30:00Z", 4, 4);
        mockMvc.perform(get("/api/fumigation-orders/{id}/gas-frames/chain", orderId))
                .andExpect(jsonPath("$.releasable").value(true));

        // 散气中增点 P3（版本9 → 10，名单版本2）
        mockMvc.perform(post("/api/fumigation-orders/{id}/gas-points/additions", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wa-add\",\"expectedVersion\":9,\"points\":[\"P3\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.listVersion").value(2))
                .andExpect(jsonPath("$.points.length()").value(3));

        // 增点后旧三帧不再连续，待解除确认失效
        mockMvc.perform(get("/api/fumigation-orders/{id}/gas-frames/chain", orderId))
                .andExpect(jsonPath("$.listVersion").value(2))
                .andExpect(jsonPath("$.runLength").value(0))
                .andExpect(jsonPath("$.releasable").value(false));
        mockMvc.perform(post("/api/fumigation-orders/{id}/release", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"wa-rel-stale\",\"expectedVersion\":10,"
                                + "\"reviewers\":[\"carol\",\"dave\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GAS_SEQUENCE_INCOMPLETE"));
    }

    /** 申请-双审批-开始，返回版本4、处于 FUMIGATING 的熏蒸单ID。 */
    private long applyAndApproveStart(long siloId, String prefix) throws Exception {
        String applyBody = "{\"requestId\":\"" + prefix + "-apply\",\"windowStart\":\"2026-09-26T00:00:00Z\","
                + "\"windowEnd\":\"2026-09-26T01:00:00Z\",\"syntheticAgent\":\"SYN-A\","
                + "\"safetyThreshold\":5.000}";
        String resp = mockMvc.perform(post("/api/silos/{id}/fumigation-orders", siloId)
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(resp, "$.id")).longValue();
        mockMvc.perform(post("/api/fumigation-orders/{id}/approvals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + prefix + "-a1\",\"expectedVersion\":1,\"approver\":\"alice\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/fumigation-orders/{id}/approvals", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + prefix + "-a2\",\"expectedVersion\":2,\"approver\":\"bob\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/fumigation-orders/{id}/start", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + prefix + "-start\",\"expectedVersion\":3,"
                                + "\"startedAt\":\"2026-09-26T00:00:00Z\"}"))
                .andExpect(status().isCreated());
        return orderId;
    }

    private void frame(long orderId, String requestId, int version, int seq, String measuredAt,
                       double p1, double p2) throws Exception {
        String readings = "[[\"P1\"," + p1 + "],[\"P2\"," + p2 + "]]";
        mockMvc.perform(post("/api/fumigation-orders/{id}/gas-frames", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(frameBody(requestId, version, seq, measuredAt, readings)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seqNo").value(seq))
                .andExpect(jsonPath("$.safe").value(true));
    }

    private String frameBody(String requestId, int version, int seq, String measuredAt, String readingsJson) {
        StringBuilder readings = new StringBuilder("[");
        String[] pairs = readingsJson.substring(1, readingsJson.length() - 1).split("\\],\\[");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i].replace("[", "").replace("]", "");
            String[] kv = pair.split(",");
            if (i > 0) {
                readings.append(',');
            }
            readings.append("{\"pointCode\":").append(kv[0]).append(",\"concentration\":").append(kv[1]).append('}');
        }
        readings.append(']');
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + ",\"seqNo\":" + seq
                + ",\"measuredAt\":\"" + measuredAt + "\",\"readings\":" + readings + "}";
    }
}
