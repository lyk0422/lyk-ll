package com.example.starter.water;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层语义测试：HTTP 状态码与统一错误响应结构。
 */
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class WaterControllerTest extends AbstractWaterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void declare_thenReplayAndConflict_overHttp() throws Exception {
        String user = newUser("R1", "1000");
        String body = """
                {"requestId":"http-1","userCode":"%s","month":"2026-01","plannedAmount":500}
                """.formatted(user);

        mockMvc.perform(post("/api/declarations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(1));

        // 同键同参重放：返回首次结果
        mockMvc.perform(post("/api/declarations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 同键异参：409 与统一错误结构
        String conflict = """
                {"requestId":"http-1","userCode":"%s","month":"2026-01","plannedAmount":600}
                """.formatted(user);
        mockMvc.perform(post("/api/declarations").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void declare_unknownUser_404() throws Exception {
        String body = """
                {"requestId":"http-2","userCode":"NO-SUCH","month":"2026-01","plannedAmount":10}
                """;
        mockMvc.perform(post("/api/declarations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void declare_invalidMonth_400() throws Exception {
        String body = """
                {"requestId":"http-3","userCode":"U1","month":"2026-13","plannedAmount":10}
                """;
        mockMvc.perform(post("/api/declarations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void quotaAndUsage_overHttp() throws Exception {
        String user = newUser("R1", "1000");
        String point = newMeterPoint(user);
        report(point, "2026-01-01T00:00:00Z", "0");
        report(point, "2026-01-31T00:00:00Z", "120");

        mockMvc.perform(get("/api/users/{u}/quota/{y}", user, 2026))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseQuota").value(1000))
                .andExpect(jsonPath("$.actualUsage").value(120));

        mockMvc.perform(get("/api/users/{u}/usage/{m}", user, "2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualUsage").value(120));
    }
}
