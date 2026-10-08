package com.example.starter;

import static org.hamcrest.Matchers.hasSize;
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
 * 热备接替 Web 全流程：100kW旧车供电中 → 60+40kW同馈线新车申请接替（恰等容量）→
 * 逐辆到场 → 两个不同操作员确认原子切换；台账区分 SUPPLY/STANDBY；撤回与复电关闭错误语义。
 */
@AutoConfigureMockMvc
class StandbyReplacementApiWebTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 29, 10, 0, 0);

    @Autowired
    private MockMvc mvc;

    private long newOrder(String requestId) throws Exception {
        String report = mvc.perform(post("/api/fault-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"feederId\":\"F01\","
                        + "\"faultPoint\":\"热备专线#" + requestId + "\",\"affectedUsers\":30,"
                        + "\"importantUser\":\"市一医院\",\"reportedAt\":\"2026-09-29T10:00:00\"}"))
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

    @Test
    void 热备接替申请到场双确认切换全流程() throws Exception {
        freezeAt(T0);
        long oldTruck = registerTruck("api-sb-old", 100);
        long t60 = registerTruck("api-sb-60", 60);
        long t40 = registerTruck("api-sb-40", 40);
        long order = newOrder("api-sb-r");

        String created = mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-d\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":1,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":100}],"
                        + "\"truckIds\":[" + oldTruck + "],"
                        + "\"dispatchedAt\":\"2026-09-29T02:00:00Z\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long dispatchId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/generator-dispatches/{id}/arrivals", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-da\",\"expectedVersion\":1,\"truckId\":" + oldTruck
                        + ",\"arrivedAt\":\"2026-09-29T02:05:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPLYING"));

        // 非SUPPLYING版本号（期望旧版本1，实际2）应409
        mvc.perform(post("/api/generator-dispatches/{id}/replacements", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-bad\",\"expectedVersion\":1,\"oldTruckId\":" + oldTruck
                        + ",\"newTruckIds\":[" + t60 + "," + t40 + "],"
                        + "\"createdAt\":\"2026-09-29T02:10:00Z\"}"))
                .andExpect(status().isConflict());

        String replacement = mvc.perform(post("/api/generator-dispatches/{id}/replacements", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-rep\",\"expectedVersion\":2,\"oldTruckId\":" + oldTruck
                        + ",\"newTruckIds\":[" + t60 + "," + t40 + "],"
                        + "\"createdAt\":\"2026-09-29T02:10:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.frozenLoadKw").value(100))
                .andExpect(jsonPath("$.oldCapacityKw").value(100))
                .andExpect(jsonPath("$.newCapacityKw").value(100))
                .andExpect(jsonPath("$.newTrucks", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        long replacementId = Long.parseLong(replacement.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 台账：旧车SUPPLY，新车STANDBY
        mvc.perform(get("/api/generator-trucks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + oldTruck + ")].occupationType",
                        org.hamcrest.Matchers.hasItem("SUPPLY")))
                .andExpect(jsonPath("$[?(@.id == " + t60 + ")].occupationType",
                        org.hamcrest.Matchers.hasItem("STANDBY")));

        // 部分到场不切换
        mvc.perform(post("/api/replacements/{id}/arrivals", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-a60\",\"expectedVersion\":1,\"truckId\":" + t60
                        + ",\"arrivedAt\":\"2026-09-29T02:15:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ARRIVED"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post("/api/replacements/{id}/arrivals", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-a40\",\"expectedVersion\":2,\"truckId\":" + t40
                        + ",\"arrivedAt\":\"2026-09-29T02:16:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALL_ARRIVED"))
                .andExpect(jsonPath("$.version").value(3));

        // 单人不切换
        mvc.perform(post("/api/replacements/{id}/switch-confirmations", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-op1\",\"expectedVersion\":3,\"confirmer\":\"操作员甲\","
                        + "\"confirmedAt\":\"2026-09-29T02:17:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switched").value(false))
                .andExpect(jsonPath("$.version").value(4));

        // 同一操作员重复确认409
        mvc.perform(post("/api/replacements/{id}/switch-confirmations", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-op1x\",\"expectedVersion\":4,\"confirmer\":\"操作员甲\","
                        + "\"confirmedAt\":\"2026-09-29T02:17:30Z\"}"))
                .andExpect(status().isConflict());

        // 第二人确认后原子切换
        mvc.perform(post("/api/replacements/{id}/switch-confirmations", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-op2\",\"expectedVersion\":4,\"confirmer\":\"操作员乙\","
                        + "\"confirmedAt\":\"2026-09-29T02:18:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.switched").value(true))
                .andExpect(jsonPath("$.status").value("SWITCHED"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.evidence.beforeTruckIds[0]").value(oldTruck))
                .andExpect(jsonPath("$.evidence.beforeCapacityKw").value(100))
                .andExpect(jsonPath("$.evidence.afterCapacityKw").value(100));

        mvc.perform(get("/api/replacements/{id}", replacementId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SWITCHED"))
                .andExpect(jsonPath("$.confirmers", hasSize(2)));
        mvc.perform(get("/api/generator-dispatches/{id}/replacements", dispatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(replacementId));
        // 切换后新车供电
        mvc.perform(get("/api/generator-trucks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + t60 + ")].occupationType",
                        org.hamcrest.Matchers.hasItem("SUPPLY")))
                .andExpect(jsonPath("$[?(@.id == " + oldTruck + ")].occupationType",
                        org.hamcrest.Matchers.hasItem(nullValue())));

        // 接替单不存在404；已切换不可撤回422
        mvc.perform(get("/api/replacements/99999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/replacements/{id}/revoke", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb-rv\",\"expectedVersion\":5}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void 未切换撤回释放全部新车() throws Exception {
        freezeAt(T0);
        long oldTruck = registerTruck("api-sb2-old", 100);
        long t100 = registerTruck("api-sb2-new", 100);
        long order = newOrder("api-sb2-r");
        String created = mvc.perform(post("/api/generator-dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb2-d\",\"workOrderId\":" + order
                        + ",\"expectedVersion\":1,"
                        + "\"loads\":[{\"userName\":\"市一医院\",\"loadKw\":100}],"
                        + "\"truckIds\":[" + oldTruck + "],"
                        + "\"dispatchedAt\":\"2026-09-29T02:00:00Z\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long dispatchId = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/generator-dispatches/{id}/arrivals", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb2-da\",\"expectedVersion\":1,\"truckId\":" + oldTruck
                        + ",\"arrivedAt\":\"2026-09-29T02:05:00Z\"}"))
                .andExpect(status().isOk());

        String rep = mvc.perform(post("/api/generator-dispatches/{id}/replacements", dispatchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb2-rep\",\"expectedVersion\":2,\"oldTruckId\":" + oldTruck
                        + ",\"newTruckIds\":[" + t100 + "],"
                        + "\"createdAt\":\"2026-09-29T02:10:00Z\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long replacementId = Long.parseLong(rep.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/replacements/{id}/revoke", replacementId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"api-sb2-rv\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.releasedTruckIds[0]").value(t100))
                .andExpect(jsonPath("$.oldTruckId").value(oldTruck))
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/api/generator-trucks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + t100 + ")].occupationType",
                        org.hamcrest.Matchers.hasItem(nullValue())))
                .andExpect(jsonPath("$[?(@.id == " + oldTruck + ")].occupationType",
                        org.hamcrest.Matchers.hasItem("SUPPLY")));
    }
}
