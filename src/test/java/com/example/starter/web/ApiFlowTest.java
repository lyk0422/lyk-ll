package com.example.starter.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.support.BaseIntegrationTest;

/**
 * Web 层全流程集成测试：母液登记→日检→分装计算→双人核对→发药勾稽→台账，
 * 覆盖 HTTP 状态语义（201/200/202/404/409/422）。
 */
@AutoConfigureMockMvc
class ApiFlowTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private long nuclideId;
    private long calibratorId;
    private long referenceSourceId;
    private long motherLiquorId;

    private void seed() throws Exception {
        nuclideId = testNuclideId();
        calibratorId = idOf(mockMvc.perform(post("/api/calibrators")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CAL-W1\",\"name\":\"活度计W1\"}"))
                .andExpect(status().isCreated()).andReturn());
        referenceSourceId = idOf(mockMvc.perform(post("/api/reference-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"calibratorId\":" + calibratorId + ",\"nuclideId\":" + nuclideId
                                + ",\"activityMbq\":200.0,\"measuredAt\":\"2026-09-26T08:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn());
        motherLiquorId = idOf(mockMvc.perform(post("/api/mother-liquors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuclideId\":" + nuclideId
                                + ",\"initialActivityMbq\":1000.0,"
                                + "\"measuredAt\":\"2026-09-26T08:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private static long idOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void 全流程_日检分装双人核对发药勾稽() throws Exception {
        seed();
        // 日检：期望 100 MBq，实测 105，恰 +5% 合法
        mockMvc.perform(post("/api/daily-checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-dc-1\",\"calibratorId\":" + calibratorId
                                + ",\"referenceSourceId\":" + referenceSourceId
                                + ",\"measuredActivityMbq\":105.0,"
                                + "\"checkedAt\":\"2026-09-26T09:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed", is(true)))
                .andExpect(jsonPath("$.deviation", is(0.05)));
        // 日检同键重放返回 200 首次结果
        mockMvc.perform(post("/api/daily-checks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-dc-1\",\"calibratorId\":" + calibratorId
                                + ",\"referenceSourceId\":" + referenceSourceId
                                + ",\"measuredActivityMbq\":105.0,"
                                + "\"checkedAt\":\"2026-09-26T09:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed", is(true)));
        // 分装计算：给药时刻 T0+1h 推算 500 MBq，处方 460，偏差约 8.7% 合法
        MvcResult dispenseResult = mockMvc.perform(post("/api/dispenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-dp-1\",\"motherLiquorId\":" + motherLiquorId
                                + ",\"prescriptionActivityMbq\":460.0,"
                                + "\"administerAt\":\"2026-09-26T09:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calculatedActivityMbq", is(500.0)))
                .andExpect(jsonPath("$.status", is("PENDING_CHECK")))
                .andReturn();
        long dispenseId = idOf(dispenseResult);
        // 双人核对：两名不同人员
        mockMvc.perform(post("/api/dispenses/{id}/checks", dispenseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-ck-1\",\"checkerId\":\"checker-a\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispenseStatus", is("FIRST_CHECKED")));
        mockMvc.perform(post("/api/dispenses/{id}/checks", dispenseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-ck-2\",\"checkerId\":\"checker-a\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/dispenses/{id}/checks", dispenseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-ck-3\",\"checkerId\":\"checker-b\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dispenseStatus", is("CHECKED")));
        // 发药勾稽：初始推算 500 = 分装 460 + 剩余 40 + 衰变残量 0
        mockMvc.perform(post("/api/dispenses/{id}/delivery", dispenseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-dv-1\",\"remainingActivityMbq\":40.0,"
                                + "\"remainingMeasuredAt\":\"2026-09-26T09:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.initialAtRefMbq", is(500.0)))
                .andExpect(jsonPath("$.dispensedAtRefMbq", is(460.0)))
                .andExpect(jsonPath("$.decayResidualMbq", is(0.0)));
        // 库存扣减生效
        mockMvc.perform(get("/api/mother-liquors/{id}", motherLiquorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingActivityMbq", is(40.0)));
        // 台账可复算
        mockMvc.perform(get("/api/mother-liquors/{id}/ledger", motherLiquorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].initialActivityMbq", is(500.0)))
                .andExpect(jsonPath("$[0].decayResidualMbq", is(0.0)));
        // 终态后迟到测量只记迟到事件（202）
        mockMvc.perform(post("/api/dispenses/{id}/measurements", dispenseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-ms-1\",\"measuredActivityMbq\":39.5,"
                                + "\"measuredAt\":\"2026-09-26T09:05:00Z\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.late", is(true)));
    }

    @Test
    void 分装偏差超限返回422且批次为拒绝终态() throws Exception {
        seed();
        mockMvc.perform(post("/api/dispenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-dp-2\",\"motherLiquorId\":" + motherLiquorId
                                + ",\"prescriptionActivityMbq\":400.0,"
                                + "\"administerAt\":\"2026-09-26T09:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is("REJECTED")));
    }

    @Test
    void 同键异参返回409() throws Exception {
        seed();
        String body = "{\"requestId\":\"w-dc-2\",\"calibratorId\":" + calibratorId
                + ",\"referenceSourceId\":" + referenceSourceId
                + ",\"measuredActivityMbq\":100.0,\"checkedAt\":\"2026-09-26T09:00:00Z\"}";
        mockMvc.perform(post("/api/daily-checks").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/daily-checks").contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("100.0", "101.0")))
                .andExpect(status().isConflict());
    }

    @Test
    void 发药勾稽不等返回422且库存不变() throws Exception {
        seed();
        MvcResult dispenseResult = mockMvc.perform(post("/api/dispenses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-dp-3\",\"motherLiquorId\":" + motherLiquorId
                                + ",\"prescriptionActivityMbq\":460.0,"
                                + "\"administerAt\":\"2026-09-26T09:00:00Z\"}"))
                .andExpect(status().isCreated()).andReturn();
        long dispenseId = idOf(dispenseResult);
        mockMvc.perform(post("/api/dispenses/{id}/checks", dispenseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-ck-4\",\"checkerId\":\"checker-a\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/dispenses/{id}/checks", dispenseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-ck-5\",\"checkerId\":\"checker-b\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/dispenses/{id}/delivery", dispenseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-dv-2\",\"remainingActivityMbq\":41.0,"
                                + "\"remainingMeasuredAt\":\"2026-09-26T09:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/mother-liquors/{id}", motherLiquorId))
                .andExpect(jsonPath("$.remainingActivityMbq", is(1000.0)));
    }

    @Test
    void 资源不存在返回404() throws Exception {
        mockMvc.perform(get("/api/mother-liquors/999999"))
                .andExpect(status().isNotFound());
    }
}
