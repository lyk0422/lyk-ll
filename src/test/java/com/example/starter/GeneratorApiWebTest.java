package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 发电车 Web 层：参数非法400、对象不存在404、同键异参409、业务拒绝422，以及组合调度主流程。
 */
@AutoConfigureMockMvc
class GeneratorApiWebTest extends AbstractRepairTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void 组合调度主流程经HTTP() throws Exception {
        freezeAt(java.time.LocalDateTime.of(2026, 9, 26, 10, 0, 0));
        String report = mvc.perform(post("/api/fault-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-1","feederId":"F01","faultPoint":"保电点#1",
                         "affectedUsers":30,"importantUser":"市一医院","reportedAt":"2026-09-26T10:00:00"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = Long.parseLong(report.replaceAll(".*\"workOrderId\":(\\d+).*", "$1"));

        String truck = mvc.perform(post("/api/generator-trucks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-2","truckCode":"GW-T1","capacityKw":1200,"feederId":"F01"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        long truckId = Long.parseLong(truck.replaceAll(".*\"truckId\":(\\d+).*", "$1"));

        String dispatch = mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"gw-3\",\"workOrderId\":" + orderId
                        + ",\"expectedVersion\":1,\"truckIds\":[" + truckId + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.totalLoadKw").value(1200))
                .andReturn().getResponse().getContentAsString();
        long dispatchId = Long.parseLong(dispatch.replaceAll(".*\"dispatchId\":(\\d+).*", "$1"));

        freezeAt(java.time.LocalDateTime.of(2026, 9, 26, 10, 10, 0));
        mvc.perform(post("/api/generator-dispatches/{id}/arrivals", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"gw-4\",\"expectedVersion\":1,\"truckId\":" + truckId
                        + ",\"arrivedAt\":\"2026-09-26T10:10:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPLYING"))
                .andExpect(jsonPath("$.supplying").value(true));

        mvc.perform(get("/api/generator-trucks/ledger").param("feederId", "F01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.truckCode=='GW-T1')].occupiedByDispatchId").exists());
    }

    @Test
    void 错误语义映射() throws Exception {
        // 参数非法 400：容量非正数（Bean 校验拦截，响应为 ProblemDetail）
        mvc.perform(post("/api/generator-trucks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-10","truckCode":"GW-T2","capacityKw":0,"feederId":"F01"}
                        """))
                .andExpect(status().isBadRequest());

        // 对象不存在 404：工单不存在
        mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-11","workOrderId":999999,"expectedVersion":1,"truckIds":[1]}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());

        // 业务拒绝 422：车总容量不足覆盖合计负荷
        freezeAt(java.time.LocalDateTime.of(2026, 9, 26, 10, 0, 0));
        String report = mvc.perform(post("/api/fault-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-12","feederId":"F01","faultPoint":"保电点#2",
                         "affectedUsers":30,"importantUser":"市一医院","reportedAt":"2026-09-26T10:00:00"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = Long.parseLong(report.replaceAll(".*\"workOrderId\":(\\d+).*", "$1"));
        String truck = mvc.perform(post("/api/generator-trucks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-13","truckCode":"GW-T3","capacityKw":100,"feederId":"F01"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long truckId = Long.parseLong(truck.replaceAll(".*\"truckId\":(\\d+).*", "$1"));
        String truck2 = mvc.perform(post("/api/generator-trucks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-15","truckCode":"GW-T4","capacityKw":1200,"feederId":"F01"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long truck2Id = Long.parseLong(truck2.replaceAll(".*\"truckId\":(\\d+).*", "$1"));
        mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"gw-14\",\"workOrderId\":" + orderId
                        + ",\"expectedVersion\":1,\"truckIds\":[" + truckId + "]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());

        // 失败不占键：同键补足参数后可成功
        mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"gw-14\",\"workOrderId\":" + orderId
                        + ",\"expectedVersion\":1,\"truckIds\":[" + truckId + "," + truck2Id + "]}"))
                .andExpect(status().isOk());
        // 同键异参 409
        mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"gw-14\",\"workOrderId\":" + orderId
                        + ",\"expectedVersion\":1,\"truckIds\":[" + truck2Id + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }
}
