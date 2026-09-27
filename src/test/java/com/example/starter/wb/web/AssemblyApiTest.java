package com.example.starter.wb.web;

import com.example.starter.wb.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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
 * 集装器装配端到端 HTTP 契约：提交→复核失效→双岗→关闭释放；400/404/409/422 语义。
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

    @BeforeEach
    void prepareLoadedManifest() throws Exception {
        // 旅客 60@80@PAX_F=4800；CARGO 29@100@CARGO_F=2900；BAGGAGE 9@100@BAG_A=900；货运业载 3800。
        String items = """
                [{"category":"PASSENGER","stationCode":"PAX_F","pieces":60,"unitWeightKg":80},
                 {"category":"CARGO","stationCode":"CARGO_F","pieces":29,"unitWeightKg":100},
                 {"category":"BAGGAGE","stationCode":"BAG_A","pieces":9,"unitWeightKg":100}]
                """;
        rest().perform(post("/api/flights/1/loadsheet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","flightId":1,"fuelKg":5000,"createdBy":"lc001","items":%s}
                                """.formatted(rid(), items)))
                .andExpect(status().isCreated());
        rest().perform(post("/api/flights/1/loadsheet/checkin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","fuelKg":5000,"operator":"a","items":%s}
                                """.formatted(rid(), items)))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/1/loadsheet/confirm-load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"loader"}""".formatted(rid())))
                .andExpect(status().isOk());
    }

    private String assemblyBody(String requestId, int expectedVersion, String itemsJson) {
        return """
                {"requestId":"%s","expectedVersion":%d,"occurredAt":"2026-09-28T08:30:00","operator":"lc001","items":%s}
                """.formatted(requestId, expectedVersion, itemsJson);
    }

    private static String validUlds() {
        return """
                [{"uldNo":"AKE-0001","uldType":"AKE","positionCode":"ULD_FWD_1","tareKg":100,"netKg":2900},
                 {"uldNo":"AKE-0002","uldType":"AKE","positionCode":"ULD_AFT_1","tareKg":100,"netKg":900}]
                """;
    }

    @Test
    void submitReviewCloseFlowReleasesOccupancies() throws Exception {
        String rid = rid();
        // 提交装配图 v1
        rest().perform(post("/api/flights/1/assembly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assemblyBody(rid, 0, validUlds())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planVersion").value(1))
                .andExpect(jsonPath("$.manifestVersion").value(1))
                .andExpect(jsonPath("$.cargoPayloadKg").value(3800.00))
                .andExpect(jsonPath("$.cargoMomentKgmm").value(58_928_000L))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.manifestStatus").value("LOADED"))
                .andExpect(jsonPath("$.items.length()").value(2));

        // 同键同参回放，不新增版本
        rest().perform(post("/api/flights/1/assembly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assemblyBody(rid, 0, validUlds())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planVersion").value(1));

        // 双岗复核 → REVIEWED
        rest().perform(post("/api/flights/1/loadsheet/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","role":"LOAD_CONTROLLER","reviewer":"lc001"}""".formatted(rid())))
                .andExpect(status().isOk());
        rest().perform(post("/api/flights/1/loadsheet/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","role":"FLIGHT_DISPATCHER","reviewer":"fd002"}""".formatted(rid())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));

        // 关闭放行：图版本匹配，原子释放实物占用
        rest().perform(post("/api/flights/1/loadsheet/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","closedBy":"captain"}""".formatted(rid())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        rest().perform(get("/api/flights/1/assembly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.releasedAt").isNotEmpty());

        rest().perform(get("/api/flights/1/assembly/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void cargoWeightMismatchReturns422AndCreatesNothing() throws Exception {
        String bad = """
                [{"uldNo":"AKE-0001","uldType":"AKE","positionCode":"ULD_FWD_1","tareKg":100,"netKg":2900},
                 {"uldNo":"AKE-0002","uldType":"AKE","positionCode":"ULD_AFT_1","tareKg":100,"netKg":901}]
                """;
        rest().perform(post("/api/flights/1/assembly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assemblyBody(rid(), 0, bad)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("货运业载")));
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        String rid = rid();
        rest().perform(post("/api/flights/1/assembly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assemblyBody(rid, 0, validUlds())))
                .andExpect(status().isOk());
        // expectedVersion 改成 1，其余相同 → 指纹不一致 409
        rest().perform(post("/api/flights/1/assembly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assemblyBody(rid, 1, validUlds())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不同业务参数")));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        rest().perform(post("/api/flights/1/assembly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // netKg 非正整数 → 400
        rest().perform(post("/api/flights/1/assembly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assemblyBody(rid(), 0, """
                                [{"uldNo":"X","uldType":"AKE","positionCode":"ULD_FWD_1","tareKg":0,"netKg":0}]""")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noActivePlanReturns404() throws Exception {
        rest().perform(get("/api/flights/1/assembly"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonexistentFlightReturns404() throws Exception {
        rest().perform(get("/api/flights/999/assembly"))
                .andExpect(status().isNotFound());
    }
}
