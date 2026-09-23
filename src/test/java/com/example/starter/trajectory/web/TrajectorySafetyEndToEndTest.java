package com.example.starter.trajectory.web;

import com.example.starter.trajectory.dto.CreateRuleVersionRequest;
import com.example.starter.trajectory.dto.ValidationRequest;
import com.example.starter.trajectory.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 HTTP 入口 + H2（MODE=MySQL）全链路测试，用例结束后上下文随之关闭。
 * 固化交付文档中的两组示例：安全轨迹与多规则失败轨迹。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TrajectorySafetyEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM validation_record");
        jdbcTemplate.update("DELETE FROM rule_version");
    }

    @Test
    @DisplayName("示例一：发布规则后提交安全轨迹，返回 safe=true；重复提交哈希一致")
    void safeTrajectoryExampleThroughHttp() throws Exception {
        publishVersion("v-demo-safe", 2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest safe = TestFixtures.safeRequest("demo-safe", "v-demo-safe", null);

        MvcResult first = mockMvc.perform(post("/api/trajectory-safety/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(safe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safe").value(true))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.requestId").value("demo-safe"))
                .andExpect(jsonPath("$.ruleVersion").value("v-demo-safe"))
                .andReturn();
        String firstHash = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("resultHash").asText();

        MvcResult repeat = mockMvc.perform(post("/api/trajectory-safety/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(safe)))
                .andExpect(status().isOk())
                .andReturn();
        String repeatHash = objectMapper.readTree(repeat.getResponse().getContentAsString())
                .get("resultHash").asText();
        assertThat(repeatHash).isEqualTo(firstHash);

        mockMvc.perform(get("/api/trajectory-safety/validations/demo-safe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultHash").value(firstHash));
    }

    @Test
    @DisplayName("示例二：多规则失败轨迹返回 R1/R3/R4/R5/R6 各一项及稳定哈希，同键异参 409")
    void multiFailureTrajectoryExampleThroughHttp() throws Exception {
        publishVersion("v-demo-multi", 1.0, 10, 5, 0.1, 0.5, 2.0);
        ValidationRequest multi = TestFixtures.multiFailureRequest("demo-multi", "v-demo-multi");

        MvcResult result = mockMvc.perform(post("/api/trajectory-safety/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(multi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safe").value(false))
                .andExpect(jsonPath("$.failureCount").value(5))
                .andExpect(jsonPath("$.failures[0].ruleId").value("R1"))
                .andExpect(jsonPath("$.failures[1].ruleId").value("R3"))
                .andExpect(jsonPath("$.failures[2].ruleId").value("R4"))
                .andExpect(jsonPath("$.failures[3].ruleId").value("R5"))
                .andExpect(jsonPath("$.failures[4].ruleId").value("R6"))
                .andExpect(jsonPath("$.failures[4].objectId").value("obs-1"))
                .andExpect(jsonPath("$.failuresByRule.R1").value(1))
                .andExpect(jsonPath("$.failuresByRule.R6").value(1))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String hash = body.get("resultHash").asText();
        assertThat(hash).matches("[0-9a-f]{64}");

        // 同一轨迹改用不同规则版本复核（不同 ruleVersion 即异参）返回 409
        publishVersion("v-other", 2.0, 10, 5, 1.0, 0.5, 2.0);
        ValidationRequest otherVersion = TestFixtures.multiFailureRequest("demo-multi", "v-other");        mockMvc.perform(post("/api/trajectory-safety/validations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(otherVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 原结果保持不变
        mockMvc.perform(get("/api/trajectory-safety/validations/demo-multi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultHash").value(hash));
    }

    private void publishVersion(String version, double maxInterval, double maxSpeed, double maxAccel,
                                double maxCurvature, double roadTolerance, double safetyDistance) throws Exception {
        CreateRuleVersionRequest body = new CreateRuleVersionRequest(version,
                TestFixtures.ruleConfig(maxInterval, maxSpeed, maxAccel,
                        maxCurvature, roadTolerance, safetyDistance));
        mockMvc.perform(post("/api/trajectory-safety/rule-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }
}
