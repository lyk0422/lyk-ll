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
 * 热备接替 Web 全流程：100kW旧车由60+40kW热备替换（容量恰等），
 * 到场→双人确认切换→台账区分供电占用与热备预占；以及 409/422 错误语义。
 */
@AutoConfigureMockMvc
class ReplacementApiWebTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private MockMvc mvc;

    private long newOrder(String requestId) throws Exception {
        String report = mvc.perform(post("/api/fault-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"feederId\":\"F01\","
                        + "\"faultPoint\":\"接替专线#" + requestId + "\",\"affectedUsers\":30,"
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
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private long supplyingDispatch(String key, long order, long truckId, int loadKw) throws Exception {
        String created = mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + key + "-d\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":1,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":" + loadKw + "}],"
                        + "\"truckIds\":[" + truckId + "],"
                        + "\"dispatchedAt\":\"2026-09-26T02:00:00Z\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long dispatchId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/generator-dispatches/{id}/arrivals", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + key + "-a\",\"expectedVersion\":1,\"truckId\":"
                        + truckId + ",\"arrivedAt\":\"2026-09-26T02:05:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALL_ARRIVED"))
                .andExpect(jsonPath("$.status").value("SUPPLYING"));
        return dispatchId;
    }

    @Test
    void 旧车100kW由60加40kW热备替换全流程() throws Exception {
        freezeAt(T0);
        long oldTruck = registerTruck("web-t1", 100);
        long n1 = registerTruck("web-t2", 60);
        long n2 = registerTruck("web-t3", 40);
        long order = newOrder("web-r1");
        long dispatchId = supplyingDispatch("web-d1", order, oldTruck, 100);

        // 申请接替：预占60+40kW新车，旧车继续供电
        String created = mvc.perform(post("/api/truck-replacements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-rp1\",\"dispatchId\":" + dispatchId
                        + ",\"expectedVersion\":2,\"oldTruckId\":" + oldTruck
                        + ",\"newTruckIds\":[" + n1 + "," + n2 + "],"
                        + "\"createdAt\":\"2026-09-26T02:10:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STANDBY"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.oldCapacityKw").value(100))
                .andExpect(jsonPath("$.newCapacityKw").value(nullValue()))
                .andExpect(jsonPath("$.trucks", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        long replacementId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 台账：旧车供电占用SUPPLY，新车热备预占STANDBY
        mvc.perform(get("/api/generator-trucks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + oldTruck + ")].occupationType",
                        org.hamcrest.Matchers.hasItem("SUPPLY")))
                .andExpect(jsonPath("$[?(@.id == " + n1 + ")].occupationType",
                        org.hamcrest.Matchers.hasItem("STANDBY")))
                .andExpect(jsonPath("$[?(@.id == " + n2 + ")].replacementId",
                        org.hamcrest.Matchers.hasItem((int) replacementId)));

        // 新车逐车到场
        mvc.perform(post("/api/truck-replacements/{id}/arrivals", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-ra1\",\"expectedVersion\":1,\"truckId\":" + n1
                        + ",\"arrivedAt\":\"2026-09-26T02:20:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ARRIVED"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/api/truck-replacements/{id}/arrivals", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-ra2\",\"expectedVersion\":2,\"truckId\":" + n2
                        + ",\"arrivedAt\":\"2026-09-26T02:25:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALL_ARRIVED"));

        // 双人确认切换：第一人记录，第二人触发原子切换
        mvc.perform(post("/api/truck-replacements/{id}/switch-confirmations", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-rc1\",\"expectedVersion\":3,"
                        + "\"operator\":\"操作员甲\",\"confirmedAt\":\"2026-09-26T02:30:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switched").value(false))
                .andExpect(jsonPath("$.status").value("STANDBY"));
        mvc.perform(post("/api/truck-replacements/{id}/switch-confirmations", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-rc2\",\"expectedVersion\":4,"
                        + "\"operator\":\"操作员乙\",\"confirmedAt\":\"2026-09-26T02:35:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switched").value(true))
                .andExpect(jsonPath("$.status").value("SWITCHED"))
                .andExpect(jsonPath("$.oldCapacityKw").value(100))
                .andExpect(jsonPath("$.newCapacityKw").value(100))
                .andExpect(jsonPath("$.switchedAt").value(notNullValue()));

        // 切换后：旧车空闲，新车转供电占用，原调度单仍SUPPLYING且组合已替换
        mvc.perform(get("/api/generator-trucks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + oldTruck + ")].occupationType",
                        org.hamcrest.Matchers.hasItem((String) null)))
                .andExpect(jsonPath("$[?(@.id == " + n1 + ")].occupationType",
                        org.hamcrest.Matchers.hasItem("SUPPLY")));
        mvc.perform(get("/api/generator-dispatches/{id}", dispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPLYING"))
                .andExpect(jsonPath("$.totalCapacityKw").value(100))
                .andExpect(jsonPath("$.suppliedAt").value(notNullValue()));
        mvc.perform(get("/api/work-orders/{id}/truck-replacements", order))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("SWITCHED"))
                .andExpect(jsonPath("$[0].confirmations", hasSize(2)));
    }

    @Test
    void 接替错误语义映射() throws Exception {
        freezeAt(T0);
        long oldTruck = registerTruck("web-t4", 100);
        long n1 = registerTruck("web-t5", 60);
        long n2 = registerTruck("web-t6", 40);
        long order = newOrder("web-r2");
        long dispatchId = supplyingDispatch("web-d2", order, oldTruck, 100);

        // 409：调度单版本不符
        mvc.perform(post("/api/truck-replacements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-e1\",\"dispatchId\":" + dispatchId
                        + ",\"expectedVersion\":99,\"oldTruckId\":" + oldTruck
                        + ",\"newTruckIds\":[" + n1 + "," + n2 + "],"
                        + "\"createdAt\":\"2026-09-26T02:10:00Z\"}"))
                .andExpect(status().isConflict());
        // 400：新车超过3辆
        mvc.perform(post("/api/truck-replacements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-e2\",\"dispatchId\":" + dispatchId
                        + ",\"expectedVersion\":2,\"oldTruckId\":" + oldTruck
                        + ",\"newTruckIds\":[1,2,3,4],"
                        + "\"createdAt\":\"2026-09-26T02:10:00Z\"}"))
                .andExpect(status().isBadRequest());

        String created = mvc.perform(post("/api/truck-replacements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-e3\",\"dispatchId\":" + dispatchId
                        + ",\"expectedVersion\":2,\"oldTruckId\":" + oldTruck
                        + ",\"newTruckIds\":[" + n1 + "," + n2 + "],"
                        + "\"createdAt\":\"2026-09-26T02:10:00Z\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long replacementId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 同键异参 409
        mvc.perform(post("/api/truck-replacements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-e3\",\"dispatchId\":" + dispatchId
                        + ",\"expectedVersion\":2,\"oldTruckId\":" + oldTruck
                        + ",\"newTruckIds\":[" + n1 + "],"
                        + "\"createdAt\":\"2026-09-26T02:10:00Z\"}"))
                .andExpect(status().isConflict());
        // 422：缺车时确认切换
        mvc.perform(post("/api/truck-replacements/{id}/switch-confirmations", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-e4\",\"expectedVersion\":1,"
                        + "\"operator\":\"操作员甲\",\"confirmedAt\":\"2026-09-26T02:20:00Z\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 404：接替单不存在
        mvc.perform(get("/api/truck-replacements/{id}", 99999L))
                .andExpect(status().isNotFound());

        // 撤回后旧车继续供电、预占释放
        mvc.perform(post("/api/truck-replacements/{id}/withdraw", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"web-e5\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.releasedTruckIds", hasSize(2)));
        mvc.perform(get("/api/generator-dispatches/{id}", dispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPLYING"));
    }
}
