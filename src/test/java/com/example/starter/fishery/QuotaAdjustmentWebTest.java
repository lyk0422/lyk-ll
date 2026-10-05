package com.example.starter.fishery;

import com.example.starter.fishery.init.SyntheticDataInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 调剂与结转 REST 入口测试：201 受理、双方确认生效、422 超量/预警、409 同键异参（真实 H2 + 完整 Spring 栈）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QuotaAdjustmentWebTest extends AbstractFisheryTest {

    private static final int YEAR = T0.atZone(ZoneOffset.UTC).getYear();
    private static final String SPECIES = SyntheticDataInitializer.SPECIES_HAIRTAIL;

    @Autowired
    private MockMvc mockMvc;

    private String vesselA;
    private String vesselB;

    @BeforeEach
    void setUp() {
        vesselA = registerVesselWithQuota(SPECIES, "100.000");
        vesselB = registerVesselWithQuota(SPECIES, "50.000");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void transferFlowOverHttp_createConfirmAndEffect() throws Exception {
        String createBody = """
                {
                  "requestId": "%s",
                  "fromVessel": "%s",
                  "toVessel": "%s",
                  "speciesCode": "%s",
                  "quotaYear": %d,
                  "amountKg": 40.000
                }
                """.formatted(newRequestId(), vesselA, vesselB, SPECIES, YEAR);

        String idJson = mockMvc.perform(post("/api/fishery/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn().getResponse().getContentAsString();
        long transferId = ((Number) com.jayway.jsonpath.JsonPath.read(idJson, "$.transferId")).longValue();

        // 同键同参重放
        mockMvc.perform(post("/api/fishery/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        // 同键异参 → 409
        mockMvc.perform(post("/api/fishery/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody.replace("40.000", "41.000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 双方确认 → 生效
        mockMvc.perform(post("/api/fishery/transfers/" + transferId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"%s\", \"vesselCode\": \"%s\"}"
                                .formatted(newRequestId(), vesselA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.fromConfirmed").value(true));
        mockMvc.perform(post("/api/fishery/transfers/" + transferId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"%s\", \"vesselCode\": \"%s\"}"
                                .formatted(newRequestId(), vesselB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

        // 台账：调出方 100→60，调入方 50→90
        mockMvc.perform(get("/api/fishery/quotas")
                        .param("vesselCode", vesselA).param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quotaKg").value(60.000))
                .andExpect(jsonPath("$[0].remainingKg").value(60.000));
        mockMvc.perform(get("/api/fishery/quotas")
                        .param("vesselCode", vesselB).param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quotaKg").value(90.000));
    }

    @Test
    void transferOverRemainingReturns422() throws Exception {
        String body = """
                {
                  "requestId": "%s",
                  "fromVessel": "%s",
                  "toVessel": "%s",
                  "speciesCode": "%s",
                  "quotaYear": %d,
                  "amountKg": 100.001
                }
                """.formatted(newRequestId(), vesselA, vesselB, SPECIES, YEAR);
        mockMvc.perform(post("/api/fishery/transfers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("整笔拒绝")));
    }

    @Test
    void carryoverOverHttp_createdAndAlertRejected() throws Exception {
        int fromYear = Instant.now().atZone(ZoneOffset.UTC).getYear() - 1;
        String vessel = registerVessel("VW-" + System.nanoTime(), java.util.Map.of());
        addQuota(vessel, SPECIES, fromYear, "1000.000");
        setUsedQuota(vessel, SPECIES, fromYear, "800.000");

        String body = """
                {
                  "requestId": "%s",
                  "vesselCode": "%s",
                  "speciesCode": "%s",
                  "fromYear": %d
                }
                """.formatted(newRequestId(), vessel, SPECIES, fromYear);
        // 恰 20%（未用 200 = 1000 × 20%）合法
        mockMvc.perform(post("/api/fishery/carryovers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountKg").value(200.000))
                .andExpect(jsonPath("$.toYear").value(fromYear + 1))
                .andExpect(jsonPath("$.replayed").value(false));

        // 重复结转 → 409
        mockMvc.perform(post("/api/fishery/carryovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(body.split("\"")[3], newRequestId())))
                .andExpect(status().isConflict());

        // 已触发产地预警的额度不得结转 → 422
        String alerted = registerVessel("VW-" + System.nanoTime(), java.util.Map.of());
        addQuota(alerted, SPECIES, fromYear, "500.000");
        setQuotaAlert(alerted, SPECIES, fromYear, true);
        mockMvc.perform(post("/api/fishery/carryovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "%s",
                                  "vesselCode": "%s",
                                  "speciesCode": "%s",
                                  "fromYear": %d
                                }
                                """.formatted(newRequestId(), alerted, SPECIES, fromYear)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("产地预警")));
    }
}
