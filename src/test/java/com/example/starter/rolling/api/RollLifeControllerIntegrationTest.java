package com.example.starter.rolling.api;

import com.example.starter.rolling.AbstractRollingIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 轧辊寿命台账 HTTP 公开契约：实绩到禁排主流程 200、缺轧辊号 400 提示补录、
 * 超限 409 不入库、同键异参 409、寿命到期上机 409 含剩余里程、台账 404。
 */
@AutoConfigureMockMvc
class RollLifeControllerIntegrationTest extends AbstractRollingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 换辊实绩到禁排主流程() throws Exception {
        seedRoll("RH1", "10", "0", 0, false, false);

        mockMvc.perform(post("/api/rolling/rolls/mount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-mt\",\"rollNo\":\"RH1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onMachine").value(true))
                .andExpect(jsonPath("$.firstUseRemaining").value(5));

        mockMvc.perform(post("/api/rolling/rolls/actuals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-a1","rollNo":"RH1","rollingKm":6,
                                 "recordedAt":"2026-09-25T09:00:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.firstUse").value(true))
                .andExpect(jsonPath("$.totalKm").value(6))
                .andExpect(jsonPath("$.banned").value(false));

        // 恰达上限：入库并立即禁排。
        mockMvc.perform(post("/api/rolling/rolls/actuals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-a2","rollNo":"RH1","rollingKm":4,
                                 "recordedAt":"2026-09-25T10:00:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalKm").value(10))
                .andExpect(jsonPath("$.remainingKm").value(0))
                .andExpect(jsonPath("$.banned").value(true));

        // 超限实绩不得入库。
        mockMvc.perform(post("/api/rolling/rolls/actuals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-a3","rollNo":"RH1","rollingKm":0.5,
                                 "recordedAt":"2026-09-25T11:00:00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 寿命到期拒绝上机并给出剩余里程。
        mockMvc.perform(post("/api/rolling/rolls/mount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-mt2\",\"rollNo\":\"RH1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("剩余里程 0")));

        // 台账稳定可查且可复算。
        mockMvc.perform(get("/api/rolling/rolls/RH1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalKm").value(10))
                .andExpect(jsonPath("$.recomputedTotalKm").value(10))
                .andExpect(jsonPath("$.banned").value(true));
    }

    @Test
    void 实绩缺轧辊号400并提示补录() throws Exception {
        seedRoll("RH2", "10", "0", 0, true, false);

        mockMvc.perform(post("/api/rolling/rolls/actuals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"h-nonum","rollingKm":1,
                                 "recordedAt":"2026-09-25T09:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("补录")));
    }

    @Test
    void 同键异参409() throws Exception {
        seedRoll("RH3", "10", "0", 0, true, false);
        String first = """
                {"requestId":"h-idem","rollNo":"RH3","rollingKm":2,
                 "recordedAt":"2026-09-25T09:00:00"}
                """;
        String second = first.replace("\"rollingKm\":2", "\"rollingKm\":3");

        mockMvc.perform(post("/api/rolling/rolls/actuals")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/rolling/rolls/actuals")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalKm").value(2));
        mockMvc.perform(post("/api/rolling/rolls/actuals")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void 查询不存在轧辊台账404() throws Exception {
        mockMvc.perform(get("/api/rolling/rolls/NOPE"))
                .andExpect(status().isNotFound());
    }
}
