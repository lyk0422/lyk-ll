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
 * 集装器装配图 HTTP 契约：提交 201、查询、400/404/409/422 语义与关闭后占用释放。
 */
class AssemblyApiTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc;

    private MockMvc rest() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        return mvc;
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static String createControlledBody(String requestId) {
        return """
                {
                  "requestId": "%s",
                  "flightId": 1,
                  "fuelKg": 5000,
                  "createdBy": "lc001",
                  "assemblyControlled": true,
                  "items": [
                    {"category": "PASSENGER", "stationCode": "PAX_F", "pieces": 90, "unitWeightKg": 80},
                    {"category": "CARGO", "stationCode": "CARGO_F", "pieces": 6, "unitWeightKg": 1000}
                  ]
                }
                """.formatted(requestId);
    }

    private static String planBody(String requestId, int expManifest, int expPlan) {
        return """
                {
                  "requestId": "%s",
                  "expectedManifestVersion": %d,
                  "expectedPlanVersion": %d,
                  "operator": "uld01",
                  "submittedAt": "2026-09-26T08:30:00",
                  "items": [
                    {"uldNumber": "ULD001", "uldType": "AKE", "compartmentCode": "ULD_P1", "tareKg": 80, "netKg": 2920},
                    {"uldNumber": "ULD002", "uldType": "AKE", "compartmentCode": "ULD_P2", "tareKg": 80, "netKg": 2920}
                  ]
                }
                """.formatted(requestId, expManifest, expPlan);
    }

    private void toLoadedControlled() throws Exception {
        rest().perform(post("/api/flights/1/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON).content(createControlledBody(rid())))
                .andExpect(status().isCreated());
        rest().perform(post("/api/flights/1/loadsheet/checkin")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"%s","fuelKg":5000,"operator":"a","items":[
                                  {"category":"PASSENGER","stationCode":"PAX_F","pieces":90,"unitWeightKg":80},
                                  {"category":"CARGO","stationCode":"CARGO_F","pieces":6,"unitWeightKg":1000}]}
                                """.formatted(rid())))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/1/loadsheet/confirm-load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"operator\":\"loader\"}".formatted(rid())))
                .andExpect(status().isOk());
    }

    @Test
    void assemblyHttpFlowSubmitQueryAndCloseRelease() throws Exception {
        toLoadedControlled();
        // 提交装配图：201，恰达限额合法，力矩精确
        rest().perform(post("/api/flights/1/loadsheet/assembly")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody(rid(), 1, 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planVersion").value(1))
                .andExpect(jsonPath("$.manifestVersionNo").value(1))
                .andExpect(jsonPath("$.totalWeightKg").value(6000))
                .andExpect(jsonPath("$.cargoMomentKgMm").value(84000000))
                .andExpect(jsonPath("$.cgZfwPct").value(17.8728))
                .andExpect(jsonPath("$.items.length()").value(2));

        // 有效装配图与版本链查询
        rest().perform(get("/api/flights/1/loadsheet/assembly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planVersion").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        rest().perform(get("/api/flights/1/loadsheet/assembly/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 集装器占用可追溯
        rest().perform(get("/api/uld/ULD001/occupation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightId").value(1));

        // 双岗复核后关闭：占用原子释放，历史图只读
        rest().perform(post("/api/flights/1/loadsheet/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"role\":\"LOAD_CONTROLLER\",\"reviewer\":\"lc001\"}"
                                .formatted(rid())))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/1/loadsheet/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"role\":\"FLIGHT_DISPATCHER\",\"reviewer\":\"fd002\"}"
                                .formatted(rid())))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/1/loadsheet/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"%s\",\"closedBy\":\"captain\"}".formatted(rid())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        rest().perform(get("/api/uld/ULD001/occupation"))
                .andExpect(status().isNotFound());
        rest().perform(get("/api/flights/1/loadsheet/assembly/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RELEASED"));
    }

    @Test
    void assemblyValidationErrorSemantics() throws Exception {
        toLoadedControlled();
        // 缺字段 → 400
        rest().perform(post("/api/flights/1/loadsheet/assembly")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // 期望版本不符 → 409
        rest().perform(post("/api/flights/1/loadsheet/assembly")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody(rid(), 1, 1)))
                .andExpect(status().isConflict());
        // 舱位不允许的集装器类型 → 422
        String badType = planBody(rid(), 1, 0).replace("\"uldType\": \"AKE\", \"compartmentCode\": \"ULD_P1\"",
                "\"uldType\": \"PLA\", \"compartmentCode\": \"ULD_P1\"");
        rest().perform(post("/api/flights/1/loadsheet/assembly")
                        .contentType(MediaType.APPLICATION_JSON).content(badType))
                .andExpect(status().isUnprocessableEntity());
        // 不存在的航班 → 404
        rest().perform(post("/api/flights/999/loadsheet/assembly")
                        .contentType(MediaType.APPLICATION_JSON).content(planBody(rid(), 1, 0)))
                .andExpect(status().isNotFound());
        // 无有效装配图 → 404
        rest().perform(get("/api/flights/1/loadsheet/assembly"))
                .andExpect(status().isNotFound());
    }
}
