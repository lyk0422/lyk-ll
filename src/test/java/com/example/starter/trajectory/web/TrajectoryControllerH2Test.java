package com.example.starter.trajectory.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TrajectoryControllerH2Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM validation_record");
        jdbcTemplate.update("DELETE FROM rule_config");
    }

    private String ruleJson() {
        return """
                {
                  "ruleVersion": "v1",
                  "maxTimeGapSeconds": 1.0,
                  "maxSpeed": 10.0,
                  "maxAcceleration": 5.0,
                  "maxCurvature": 0.1,
                  "roadTolerance": 0.2,
                  "collisionDistance": 2.0
                }
                """;
    }

    private String safeValidationJson(String requestId) {
        return """
                {
                  "requestId": "%s",
                  "ruleVersion": "v1",
                  "vehicle": {"position": {"x": 50, "y": 50}, "heading": 0, "speed": 4},
                  "trajectory": [
                    {"t": 0.5, "position": {"x": 52, "y": 50}, "heading": 0},
                    {"t": 1.0, "position": {"x": 54, "y": 50}, "heading": 0}
                  ],
                  "obstacles": [
                    {"objectId": "OBS-1", "points": [
                      {"t": 0, "position": {"x": 90, "y": 90}},
                      {"t": 1, "position": {"x": 90, "y": 90}}
                    ]}
                  ],
                  "road": [{"x": 0, "y": 0}, {"x": 100, "y": 0}, {"x": 100, "y": 100}, {"x": 0, "y": 100}]
                }
                """.formatted(requestId);
    }

    @Test
    void fullHttpFlowSafeThenReplayThenConflict() throws Exception {
        mockMvc.perform(post("/api/trajectory/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(post("/api/trajectory/rules/v1/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        mockMvc.perform(post("/api/trajectory/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(safeValidationJson("http-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safe").value(true))
                .andExpect(jsonPath("$.requestId").value("http-1"));

        mockMvc.perform(post("/api/trajectory/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(safeValidationJson("http-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safe").value(true));

        String conflicting = safeValidationJson("http-1").replace("\"x\": 52", "\"x\": 60");
        mockMvc.perform(post("/api/trajectory/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflicting))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void publishedRuleUpdateIsRejectedWith409() throws Exception {
        mockMvc.perform(post("/api/trajectory/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/trajectory/rules/v1/publish"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/trajectory/rules/v1/publish"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/trajectory/rules/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleJson()))
                .andExpect(status().isConflict());
    }
}
