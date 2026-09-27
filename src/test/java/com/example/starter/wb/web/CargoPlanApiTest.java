package com.example.starter.wb.web;

import com.example.starter.wb.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集装器装配图 HTTP 契约：主流程、400 参数校验、404/409 语义与历史只读查询。
 */
class CargoPlanApiTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc;

    private MockMvc rest() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        return mvc;
    }

    private void flight1ToLoaded() throws Exception {
        rest().perform(post("/api/flights/1/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","flightId":1,"fuelKg":5000,"createdBy":"lc001","items":[
                                  {"category":"PASSENGER","stationCode":"PAX_F","pieces":90,"unitWeightKg":80},
                                  {"category":"CARGO","stationCode":"CARGO_F","pieces":5,"unitWeightKg":1000}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());
        rest().perform(post("/api/flights/1/loadsheet/checkin")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","fuelKg":5000,"operator":"a","items":[
                                  {"category":"PASSENGER","stationCode":"PAX_F","pieces":90,"unitWeightKg":80},
                                  {"category":"CARGO","stationCode":"CARGO_F","pieces":5,"unitWeightKg":1000}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/1/loadsheet/confirm-load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"loader"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    private String planBody(String requestId, int expectedVersion, String uldNo, String compartment,
                            int tare, int net) {
        return """
                {"requestId":"%s","expectedVersion":%d,"occurredAt":"2026-09-27T09:00:00",
                 "createdBy":"lc001","items":[{"uldNo":"%s","compartmentCode":"%s","tareKg":%d,"netKg":%d}]}
                """.formatted(requestId, expectedVersion, uldNo, compartment, tare, net);
    }

    @Test
    void submitQueryHistoryReviewCloseOverHttp() throws Exception {
        flight1ToLoaded();
        String rid = UUID.randomUUID().toString();
        rest().perform(post("/api/flights/1/cargo-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(rid, 0, "AKE12345CA", "11P", 100, 4900)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planVersion").value(1))
                .andExpect(jsonPath("$.manifestVersionNo").value(1))
                .andExpect(jsonPath("$.cargoGrossKg").value(5000))
                .andExpect(jsonPath("$.totalMomentKgMm").value(63500000))
                .andExpect(jsonPath("$.withinLimits").value(true))
                .andExpect(jsonPath("$.manifestVersionMatch").value(true))
                .andExpect(jsonPath("$.items[0].armMm").value(12700));

        // 同键同参回放 201、不新增图
        rest().perform(post("/api/flights/1/cargo-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(rid, 0, "AKE12345CA", "11P", 100, 4900)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planVersion").value(1));

        // 当前图与历史
        rest().perform(get("/api/flights/1/cargo-plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planVersion").value(1));
        rest().perform(get("/api/flights/1/cargo-plan/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 双岗 + 关闭
        rest().perform(post("/api/flights/1/loadsheet/review").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","role":"LOAD_CONTROLLER","reviewer":"lc001"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/1/loadsheet/review").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","role":"FLIGHT_DISPATCHER","reviewer":"fd002"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));
        rest().perform(post("/api/flights/1/loadsheet/close").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","closedBy":"captain"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 历史图关闭后仍只读可查
        rest().perform(get("/api/flights/1/cargo-plan/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void invalidParamsReturn400AndBusinessRejection422AndUnknownFlight404() throws Exception {
        // 缺 occurredAt → 400
        rest().perform(post("/api/flights/1/cargo-plan")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"x","expectedVersion":0,"createdBy":"lc","items":[]}"""))
                .andExpect(status().isBadRequest());

        // 净重非正 → 400；皮重为负 → 400
        rest().perform(post("/api/flights/1/cargo-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID().toString(), 0, "AKE12345CA", "11P", -1, 5001)))
                .andExpect(status().isBadRequest());

        // 航班不存在 → 404
        rest().perform(get("/api/flights/999/cargo-plan"))
                .andExpect(status().isNotFound());

        flight1ToLoaded();
        // 业务拒绝：含皮重总重与货运业载不一致 → 422
        rest().perform(post("/api/flights/1/cargo-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID().toString(), 0, "AKE12345CA", "11P", 100, 4899)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("货运业载")));
        // 从未提交即查询 → 404
        rest().perform(get("/api/flights/1/cargo-plan"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameUldOnSecondOpenFlightConflicts409() throws Exception {
        flight1ToLoaded();
        rest().perform(post("/api/flights/1/cargo-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID().toString(), 0, "AKE12345CA", "11P", 100, 4900)))
                .andExpect(status().isCreated());

        // 第二航班也走到 LOADED 后抢同器 → 409
        rest().perform(post("/api/flights/2/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","flightId":2,"fuelKg":5000,"createdBy":"lc002","items":[
                                  {"category":"PASSENGER","stationCode":"PAX_F","pieces":90,"unitWeightKg":80},
                                  {"category":"CARGO","stationCode":"CARGO_F","pieces":5,"unitWeightKg":1000}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated());
        rest().perform(post("/api/flights/2/loadsheet/checkin")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","fuelKg":5000,"operator":"a","items":[
                                  {"category":"PASSENGER","stationCode":"PAX_F","pieces":90,"unitWeightKg":80},
                                  {"category":"CARGO","stationCode":"CARGO_F","pieces":5,"unitWeightKg":1000}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/2/loadsheet/confirm-load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"loader2"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/2/cargo-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(UUID.randomUUID().toString(), 0, "AKE12345CA", "11P", 100, 4900)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已被未关闭航班")));
    }
}
