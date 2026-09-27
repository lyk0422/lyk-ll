package com.example.starter;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 发电车保电 Web 全流程：登记车辆→组合调度→到场供电→台账；超时→整体改派；
 * 以及 400/404/409/422 错误语义映射。
 */
@AutoConfigureMockMvc
class GeneratorApiWebTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private MockMvc mvc;

    private long newOrder(String requestId) throws Exception {
        String report = mvc.perform(post("/api/fault-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"feederId\":\"F01\","
                        + "\"faultPoint\":\"保电专线#" + requestId + "\",\"affectedUsers\":30,"
                        + "\"importantUser\":\"市一医院\",\"reportedAt\":\"2026-09-26T10:00:00\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(report.replaceAll(".*\"workOrderId\":(\\d+).*", "$1"));
    }

    private long registerTruck(String requestId, int capacityKw) throws Exception {
        String body = mvc.perform(post("/api/generator-trucks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"capacityKw\":" + capacityKw
                        + ",\"feederId\":\"F01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.available").value(true))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void 登记调度到场供电全流程与台账查询() throws Exception {
        freezeAt(T0);
        long truck = registerTruck("api-t1", 250);
        long order = newOrder("api-r1");

        String created = mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-d1\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":1,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":200}],"
                        + "\"truckIds\":[" + truck + "],"
                        + "\"dispatchedAt\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.orderVersion").value(1))
                .andExpect(jsonPath("$.totalLoadKw").value(200))
                .andExpect(jsonPath("$.totalCapacityKw").value(250))
                .andExpect(jsonPath("$.suppliedAt").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        long dispatchId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/api/generator-trucks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + truck + ")].dispatchId",
                        org.hamcrest.Matchers.hasItem((int) dispatchId)));

        mvc.perform(post("/api/generator-dispatches/{id}/arrivals", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-a1\",\"expectedVersion\":1,\"truckId\":" + truck
                        + ",\"arrivedAt\":\"2026-09-26T02:10:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALL_ARRIVED"))
                .andExpect(jsonPath("$.status").value("SUPPLYING"))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/generator-dispatches/{id}", dispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPLYING"))
                .andExpect(jsonPath("$.suppliedAt").value(notNullValue()));

        mvc.perform(get("/api/work-orders/{id}/generator-dispatches", order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("SUPPLYING"));
    }

    @Test
    void 超时检查后整体改派() throws Exception {
        freezeAt(T0);
        long t1 = registerTruck("api-t2", 200);
        long t2 = registerTruck("api-t3", 300);
        long order = newOrder("api-r2");

        String created = mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-d2\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":1,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":180}],"
                        + "\"truckIds\":[" + t1 + "],"
                        + "\"dispatchedAt\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long dispatchId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        freezeAt(T0.plusMinutes(30));
        mvc.perform(post("/api/generator-dispatches/timeout-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timedOut[0]").value(dispatchId));

        mvc.perform(post("/api/generator-dispatches/{id}/reassign", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-rz1\",\"expectedVersion\":2,"
                        + "\"newTruckIds\":[" + t2 + "],"
                        + "\"dispatchedAt\":\"2026-09-26T02:35:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldDispatchId").value(dispatchId))
                .andExpect(jsonPath("$.keptTruckIds", hasSize(0)))
                .andExpect(jsonPath("$.releasedTruckIds[0]").value(t1))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void 错误语义映射() throws Exception {
        freezeAt(T0);
        // 400：容量非正整数
        mvc.perform(post("/api/generator-trucks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-e1\",\"capacityKw\":0,\"feederId\":\"F01\"}"))
                .andExpect(status().isBadRequest());
        // 404：车辆不存在
        mvc.perform(post("/api/generator-trucks/{id}/availability", 99999L)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-e2\",\"expectedVersion\":1,\"available\":false}"))
                .andExpect(status().isNotFound());
        // 404：调度单不存在
        mvc.perform(get("/api/generator-dispatches/{id}", 99999L))
                .andExpect(status().isNotFound());

        long truck = registerTruck("api-e3", 100);
        long order = newOrder("api-r3");
        // 422：容量不足覆盖负荷
        mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-e4\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":1,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":200}],"
                        + "\"truckIds\":[" + truck + "],"
                        + "\"dispatchedAt\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 409：工单版本不符
        mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-e5\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":99,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":50}],"
                        + "\"truckIds\":[" + truck + "],"
                        + "\"dispatchedAt\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isConflict());
        // 成功创建后同键异参 409
        mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-e6\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":1,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":50}],"
                        + "\"truckIds\":[" + truck + "],"
                        + "\"dispatchedAt\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-e6\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":1,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":60}],"
                        + "\"truckIds\":[" + truck + "],"
                        + "\"dispatchedAt\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isConflict());
    }
}
