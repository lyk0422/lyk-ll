package com.example.starter;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
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
 * 发电车组合调度 HTTP 层：登记→调度→到场供电主流程，以及 400/404/409/422 状态码映射。
 */
@AutoConfigureMockMvc
class GeneratorApiWebTest extends AbstractRepairTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void 发电车登记调度到场全流程() throws Exception {
        // 先建特级工单
        String report = mvc.perform(post("/api/fault-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-1","feederId":"F01","faultPoint":"Web专线#9",
                         "affectedUsers":20,"importantUser":"市一医院","reportedAt":"2026-09-26T10:00:00"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long orderId = Long.parseLong(report.replaceAll(".*\"workOrderId\":(\\d+).*", "$1"));

        // 登记两台车
        mvc.perform(post("/api/generator/vehicles").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-v1","code":"Web车A","capacityKw":500,
                         "feederId":"F01","available":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(greaterThan(0)))
                .andExpect(jsonPath("$.occupied").value(false));
        mvc.perform(post("/api/generator/vehicles").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-v2","code":"Web车B","capacityKw":300,
                         "feederId":"F01","available":true}"""))
                .andExpect(status().isOk());

        // 车辆台账
        mvc.perform(get("/api/generator/vehicles").param("feederId", "F01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='Web车A')]").exists());

        // 组合调度（恰等 800kW），UTC 时刻
        String dispatch = mvc.perform(post("/api/generator/dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-d1","workOrderId":%d,"expectedVersion":1,
                         "vehicleIds":[%s,%s],"loads":[{"userName":"市一医院","loadKw":800}],
                         "dispatchedAt":"2026-09-26T02:00:00"}"""
                        .formatted(orderId, vehicleId("Web车A"), vehicleId("Web车B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.deadlineAt").value("2026-09-26T02:30:00"))
                .andReturn().getResponse().getContentAsString();
        long genOrderId = Long.parseLong(dispatch.replaceAll(".*\"orderId\":(\\d+).*", "$1"));

        // 同键同参重放
        mvc.perform(post("/api/generator/dispatches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-d1","workOrderId":%d,"expectedVersion":1,
                         "vehicleIds":[%s,%s],"loads":[{"userName":"市一医院","loadKw":800}],
                         "dispatchedAt":"2026-09-26T02:00:00"}"""
                        .formatted(orderId, vehicleId("Web车A"), vehicleId("Web车B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 逐车到场，第二台全到场供电
        mvc.perform(post("/api/generator/arrivals").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-a1","orderId":%d,"expectedVersion":1,"vehicleId":%s,
                         "arrivedAt":"2026-09-26T02:10:00"}"""
                        .formatted(genOrderId, vehicleId("Web车A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL_ARRIVAL"))
                .andExpect(jsonPath("$.supplied").value(false));
        mvc.perform(post("/api/generator/arrivals").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-a2","orderId":%d,"expectedVersion":2,"vehicleId":%s,
                         "arrivedAt":"2026-09-26T02:20:00"}"""
                        .formatted(genOrderId, vehicleId("Web车B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POWER_SUPPLIED"))
                .andExpect(jsonPath("$.supplied").value(true));

        // 详情与占用台账
        mvc.perform(get("/api/generator/orders/{id}", genOrderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles", hasSize(2)))
                .andExpect(jsonPath("$.events[0]").value(org.hamcrest.Matchers.startsWith("DISPATCHED")));
        mvc.perform(get("/api/generator/occupations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$[?(@.orderId == " + genOrderId + " && @.releasedAt == null)]", hasSize(2)));
    }

    @Test
    void 发电车接口状态码映射_400_404_422() throws Exception {
        long orderId = createOrder();
        long vehicleId = createVehicle("HTTP车", 500);

        // 容量不足 422
        mvc.perform(post("/api/generator/dispatches").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-bad1","workOrderId":%d,"expectedVersion":1,
                         "vehicleIds":[%d],"loads":[{"userName":"市一医院","loadKw":800}],
                         "dispatchedAt":"2026-09-26T02:00:00"}""".formatted(orderId, vehicleId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());

        // 缺必填字段 400
        mvc.perform(post("/api/generator/dispatches").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"","workOrderId":%d,"expectedVersion":1,
                         "vehicleIds":[%d],"loads":[{"userName":"市一医院","loadKw":800}],
                         "dispatchedAt":"2026-09-26T02:00:00"}""".formatted(orderId, vehicleId)))
                .andExpect(status().isBadRequest());

        // 工单不存在 404
        mvc.perform(post("/api/generator/dispatches").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-bad2","workOrderId":999999,"expectedVersion":1,
                         "vehicleIds":[%d],"loads":[{"userName":"市一医院","loadKw":100}],
                         "dispatchedAt":"2026-09-26T02:00:00"}""".formatted(vehicleId)))
                .andExpect(status().isNotFound());

        // 调度单不存在 404
        mvc.perform(get("/api/generator/orders/888888"))
                .andExpect(status().isNotFound());
    }

    private long createOrder() throws Exception {
        String body = mvc.perform(post("/api/fault-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"gw-seed","feederId":"F01","faultPoint":"Web点Z",
                         "affectedUsers":10,"importantUser":"市一医院","reportedAt":"2026-09-26T10:00:00"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"workOrderId\":(\\d+).*", "$1"));
    }

    private long createVehicle(String code, int kw) throws Exception {
        String body = mvc.perform(post("/api/generator/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"gw-seed-v-" + code + "\",\"code\":\"" + code
                        + "\",\"capacityKw\":" + kw + ",\"feederId\":\"F01\",\"available\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private Object vehicleId(String code) throws Exception {
        return jdbc.queryForObject("SELECT id FROM generator_vehicle WHERE code = ?", Long.class, code);
    }
}
